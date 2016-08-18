// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content;

import com.yahoo.vespa.config.search.DispatchConfig;
import com.yahoo.vespa.config.search.core.ProtonConfig;
import com.yahoo.documentmodel.DocumentTypeRepo;
import com.yahoo.documentmodel.NewDocumentType;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.vespa.model.builder.UserConfigBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomSearchTuningBuilder;
import com.yahoo.vespa.model.builder.xml.dom.ModelElement;
import com.yahoo.vespa.model.builder.xml.dom.VespaDomBuilder;
import com.yahoo.vespa.model.content.cluster.ContentCluster;
import com.yahoo.vespa.model.content.cluster.DomResourceLimitsBuilder;
import com.yahoo.vespa.model.search.*;
import org.w3c.dom.Element;


import java.util.*;

/**
 * Encapsulates the various options for search in a content model.
 * Wraps a search cluster from com.yahoo.vespa.model.search.
 */
public class ContentSearchCluster extends AbstractConfigProducer implements ProtonConfig.Producer, DispatchConfig.Producer {

    private final boolean flushOnShutdown;

    /** If this is set up for streaming search, it is modelled as one search cluster per search definition */
    private Map<String, AbstractSearchCluster>  clusters = new TreeMap<>();

    /** The single, indexed search cluster this sets up (supporting multiple document types), or null if none */
    private IndexedSearchCluster indexedCluster;

    private final String clusterName;
    Map<String, NewDocumentType> documentDefinitions;

    /** The search nodes of this if it does not have an indexed cluster */
    List<SearchNode> nonIndexed = new ArrayList<>();

    Map<StorageGroup, NodeSpec> groupToSpecMap = new LinkedHashMap<>();
    private DocumentTypeRepo repo = null;
    private Optional<ResourceLimits> resourceLimits = Optional.empty();

    public void prepare() {
        repo = getRoot().getDeployState().getDocumentModel().getDocumentManager();
    }

    public static class Builder extends VespaDomBuilder.DomConfigProducerBuilder<ContentSearchCluster> {

        private final Map<String, NewDocumentType> documentDefinitions;

        public Builder(Map<String, NewDocumentType> documentDefinitions) {
            this.documentDefinitions = documentDefinitions;
        }

        @Override
        protected ContentSearchCluster doBuild(AbstractConfigProducer ancestor, Element producerSpec) {
            ModelElement clusterElem = new ModelElement(producerSpec);
            String clusterName = ContentCluster.getClusterName(clusterElem);
            Boolean flushOnShutdown = clusterElem.childAsBoolean("engine.proton.flush-on-shutdown");

            ContentSearchCluster search = new ContentSearchCluster(ancestor, clusterName, documentDefinitions, flushOnShutdown != null ? flushOnShutdown : false);

            ModelElement tuning = clusterElem.getChildByPath("engine.proton.tuning");
            if (tuning != null) {
                search.setTuning(new DomSearchTuningBuilder().build(search, tuning.getXml()));
            }
            ModelElement protonElem = clusterElem.getChildByPath("engine.proton");
            if (protonElem != null) {
                search.setResourceLimits(DomResourceLimitsBuilder.build(protonElem));
            }

            buildAllStreamingSearchClusters(clusterElem, clusterName, search);
            buildIndexedSearchCluster(clusterElem, clusterName, search);
            return search;
        }

        private Double getQueryTimeout(ModelElement clusterElem) {
            return clusterElem.childAsDouble("engine.proton.query-timeout");
        }

        private void buildAllStreamingSearchClusters(ModelElement clusterElem, String clusterName, ContentSearchCluster search) {
            ModelElement docElem = clusterElem.getChild("documents");

            if (docElem == null) {
                return;
            }

            for (ModelElement docType : docElem.subElements("document")) {
                String mode = docType.getStringAttribute("mode");
                if ("streaming".equals(mode)) {
                    buildStreamingSearchCluster(clusterElem, clusterName, search, docType);
                }
            }
        }

        private void buildStreamingSearchCluster(ModelElement clusterElem, String clusterName, ContentSearchCluster search, ModelElement docType) {
            StreamingSearchCluster cluster = new StreamingSearchCluster(search, clusterName + "." + docType.getStringAttribute("type"), 0, clusterName, clusterName);

            List<ModelElement> def = new ArrayList<>();
            def.add(docType);
            search.addSearchCluster(cluster, getQueryTimeout(clusterElem), def);
        }

        private void buildIndexedSearchCluster(ModelElement clusterElem,
                                               String clusterName,
                                               ContentSearchCluster search) {
            List<ModelElement> indexedDefs = getIndexedSearchDefinitions(clusterElem);
            if (!indexedDefs.isEmpty()) {
                IndexedSearchCluster isc = new IndexedElasticSearchCluster(search, clusterName, 0);
                isc.setRoutingSelector(clusterElem.childAsString("documents.selection"));

                Double visibilityDelay = clusterElem.childAsDouble("engine.proton.visibility-delay");
                if (visibilityDelay != null) {
                    isc.setVisibilityDelay(visibilityDelay);
                }

                search.addSearchCluster(isc, getQueryTimeout(clusterElem), indexedDefs);
            }
        }

        private List<ModelElement> getIndexedSearchDefinitions(ModelElement clusterElem) {
            List<ModelElement> indexedDefs = new ArrayList<>();
            ModelElement docElem = clusterElem.getChild("documents");
            if (docElem == null) {
                return indexedDefs;
            }

            for (ModelElement docType : docElem.subElements("document")) {
                String mode = docType.getStringAttribute("mode");
                if ("index".equals(mode)) {
                    indexedDefs.add(docType);
                }
            }
            return indexedDefs;
        }
    }

    private ContentSearchCluster(AbstractConfigProducer parent,
                                 String clusterName,
                                 Map<String, NewDocumentType> documentDefinitions, boolean flushOnShutdown)
    {
        super(parent, "search");
        this.clusterName = clusterName;
        this.documentDefinitions = documentDefinitions;
        this.flushOnShutdown = flushOnShutdown;
    }

    void addSearchCluster(SearchCluster cluster, Double queryTimeout, List<ModelElement> documentDefs) {
        addSearchDefinitions(documentDefs, cluster);

        if (queryTimeout != null) {
            cluster.setQueryTimeout(queryTimeout);
        }
        cluster.defaultDocumentsConfig();
        cluster.deriveSearchDefinitions(new ArrayList<>());
        addCluster(cluster);
    }

    private void addSearchDefinitions(List<ModelElement> searchDefs, AbstractSearchCluster sc) {
        for (ModelElement e : searchDefs) {
            SearchDefinitionXMLHandler searchDefinitionXMLHandler = new SearchDefinitionXMLHandler(e);
            SearchDefinition searchDefinition =
                    searchDefinitionXMLHandler.getResponsibleSearchDefinition(sc.getRoot().getDeployState().getSearchDefinitions());
            if (searchDefinition == null)
                throw new RuntimeException("Search definition parsing error or file does not exist: '" +
                        searchDefinitionXMLHandler.getName() + "'");

            // TODO: remove explicit building of user configs when the complete content model is built using builders.
            sc.getLocalSDS().add(new AbstractSearchCluster.SearchDefinitionSpec(searchDefinition,
                    UserConfigBuilder.build(e.getXml(), sc.getRoot().getDeployState(), sc.getRoot().deployLogger())));
            //need to get the document names from this sdfile
            sc.addDocumentNames(searchDefinition);
        }
    }

    public ContentSearchCluster addCluster(AbstractSearchCluster sc) {
        if (clusters.containsKey(sc.getClusterName())) {
            throw new IllegalArgumentException("I already have registered cluster '" + sc.getClusterName() + "'");
        }
        if (sc instanceof IndexedSearchCluster) {
            if (indexedCluster != null) {
                throw new IllegalArgumentException("I already have one indexed cluster named '" + indexedCluster.getClusterName());
            }
            indexedCluster = (IndexedSearchCluster)sc;
        }
        clusters.put(sc.getClusterName(), sc);
        return this;
    }

    public List<SearchNode> getSearchNodes() {
        return hasIndexedCluster() ? getIndexed().getSearchNodes() : nonIndexed;
    }

    public SearchNode addSearchNode(ContentNode node, StorageGroup parentGroup, ModelElement element) {
        AbstractConfigProducer parent = hasIndexedCluster() ? getIndexed() : this;

        NodeSpec spec = getNextSearchNodeSpec(parentGroup);
        SearchNode snode;
        TransactionLogServer tls;
        if (element == null) {
            snode = SearchNode.create(parent, "" + node.getDistributionKey(), node.getDistributionKey(), spec, clusterName, node, flushOnShutdown);
            snode.setHostResource(node.getHostResource());
            snode.initService();

            tls = new TransactionLogServer(snode, clusterName);
            tls.setHostResource(snode.getHostResource());
            tls.initService();
        } else {
            snode = new SearchNode.Builder(""+node.getDistributionKey(), spec, clusterName, node, flushOnShutdown).build(parent, element.getXml());
            tls = new TransactionLogServer.Builder(clusterName).build(snode, element.getXml());
        }
        snode.setTls(tls);
        if (hasIndexedCluster()) {
            getIndexed().addSearcher(snode);
        } else {
            nonIndexed.add(snode);
        }
        return snode;
    }

    /** Translates group ids to continuous 0-base "row" id integers */
    private NodeSpec getNextSearchNodeSpec(StorageGroup parentGroup) {
        NodeSpec spec = groupToSpecMap.get(parentGroup);
        if (spec == null) {
            spec = new NodeSpec(groupToSpecMap.size(), 0);
        } else {
            spec = new NodeSpec(spec.groupIndex(), spec.partitionId() + 1);
        }
        groupToSpecMap.put(parentGroup, spec);
        return spec;
    }

    private Tuning tuning;

    public void setTuning(Tuning t) {
        tuning = t;
    }

    public void setResourceLimits(ResourceLimits resourceLimits) {
        this.resourceLimits = Optional.of(resourceLimits);
    }

    public boolean usesHierarchicDistribution() {
        return indexedCluster != null && groupToSpecMap.size() > 1;
    }

    public void handleRedundancy(Redundancy redundancy) {
        if (usesHierarchicDistribution()) {
            indexedCluster.setMaxNodesDownPerFixedRow((redundancy.effectiveFinalRedundancy() / groupToSpecMap.size()) - 1);
        }
    }

    @Override
    public void getConfig(ProtonConfig.Builder builder) {
        double visibilityDelay = hasIndexedCluster() ? getIndexed().getVisibilityDelay() : 0.0;
        for (NewDocumentType type : documentDefinitions.values()) {
            ProtonConfig.Documentdb.Builder ddbB = new ProtonConfig.Documentdb.Builder();
            ddbB.inputdoctypename(type.getFullName().getName())
                .configid(getConfigId())
                .visibilitydelay(visibilityDelay);
            if (hasIndexedCluster()) {
                getIndexed().fillDocumentDBConfig(type.getFullName().getName(), ddbB);
            }
            builder.documentdb(ddbB);
        }
        for (AbstractSearchCluster sc : getClusters().values()) {
            if (sc instanceof StreamingSearchCluster) {
                NewDocumentType type = repo.getDocumentType(((StreamingSearchCluster)sc).getSdConfig().getSearch().getName());
                ProtonConfig.Documentdb.Builder ddbB = new ProtonConfig.Documentdb.Builder();
                ddbB.inputdoctypename(type.getFullName().getName()).configid(getConfigId());
                builder.documentdb(ddbB);
            }
        }
        int numDocumentDbs = builder.documentdb.size();
        builder.initialize(new ProtonConfig.Initialize.Builder().threads(numDocumentDbs + 1));

        if (resourceLimits.isPresent()) {
            resourceLimits.get().getConfig(builder);
        }

        if (tuning != null) {
            tuning.getConfig(builder);
        }
    }

    @Override
    public void getConfig(DispatchConfig.Builder builder) {
        if (hasIndexedCluster()) {
            getIndexed().getConfig(builder);
        }
    }

    public Map<String, AbstractSearchCluster> getClusters() { return clusters; }
    public IndexedSearchCluster getIndexed() { return indexedCluster; }
    public boolean hasIndexedCluster()       { return indexedCluster != null; }
    public String getClusterName() { return clusterName; }
}
