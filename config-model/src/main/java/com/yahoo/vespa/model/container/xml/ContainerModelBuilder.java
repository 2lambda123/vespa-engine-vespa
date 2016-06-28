// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.xml;

import com.google.common.collect.ImmutableList;
import com.yahoo.component.Version;
import com.yahoo.config.application.Xml;
import com.yahoo.config.model.ConfigModelContext;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.application.provider.IncludeDirs;
import com.yahoo.config.model.builder.xml.ConfigModelBuilder;
import com.yahoo.config.model.builder.xml.ConfigModelId;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.container.jdisc.config.MetricDefaultsConfig;
import com.yahoo.search.rendering.RendererRegistry;
import com.yahoo.text.XML;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.model.AbstractService;
import com.yahoo.vespa.model.HostResource;
import com.yahoo.vespa.model.builder.xml.dom.DomClientProviderBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomComponentBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomFilterBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomHandlerBuilder;
import com.yahoo.vespa.model.builder.xml.dom.ModelElement;
import com.yahoo.vespa.model.builder.xml.dom.NodesSpecification;
import com.yahoo.vespa.model.builder.xml.dom.VespaDomBuilder;
import com.yahoo.vespa.model.builder.xml.dom.ServletBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.docproc.DomDocprocChainsBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.processing.DomProcessingBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.search.DomSearchChainsBuilder;
import com.yahoo.vespa.model.clients.ContainerDocumentApi;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.ContainerCluster;
import com.yahoo.vespa.model.container.ContainerModel;
import com.yahoo.vespa.model.container.component.Component;
import com.yahoo.vespa.model.container.component.FileStatusHandlerComponent;
import com.yahoo.vespa.model.container.component.chain.ProcessingHandler;
import com.yahoo.vespa.model.container.docproc.ContainerDocproc;
import com.yahoo.vespa.model.container.docproc.DocprocChains;
import com.yahoo.vespa.model.container.http.Http;
import com.yahoo.vespa.model.container.http.xml.HttpBuilder;
import com.yahoo.vespa.model.container.jersey.xml.RestApiBuilder;
import com.yahoo.vespa.model.container.processing.ProcessingChains;
import com.yahoo.vespa.model.container.search.ContainerSearch;
import com.yahoo.vespa.model.container.search.PageTemplates;
import com.yahoo.vespa.model.container.search.QueryProfiles;
import com.yahoo.vespa.model.container.search.SemanticRules;
import com.yahoo.vespa.model.container.search.searchchain.SearchChains;
import com.yahoo.vespa.model.container.xml.document.DocumentFactoryBuilder;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author tonytv
 */
public class ContainerModelBuilder extends ConfigModelBuilder<ContainerModel> {

    /**
     * Default path to vip status file for container in Hosted Vespa.
     */
    static final String HOSTED_VESPA_STATUS_FILE = Defaults.getDefaults().vespaHome() + "var/mediasearch/oor/status.html";
    /**
     * Path to vip status file for container in Hosted Vespa. Only used if set, else use HOSTED_VESPA_STATUS_FILE
     */
    static final String HOSTED_VESPA_STATUS_FILE_YINST_SETTING = "cloudconfig_server__tenant_vip_status_file";

    public enum Networking { disable, enable }

    private ApplicationPackage app;
    private final boolean standaloneBuilder;
    private final Networking networking;
    protected DeployLogger log;

    public static final List<ConfigModelId> configModelIds =  
            ImmutableList.of(ConfigModelId.fromName("container"), ConfigModelId.fromName("jdisc"));

    private static final String xmlRendererId = RendererRegistry.xmlRendererId.getName();
    private static final String jsonRendererId = RendererRegistry.jsonRendererId.getName();

    public ContainerModelBuilder(boolean standaloneBuilder, Networking networking) {
        super(ContainerModel.class);
        this.standaloneBuilder = standaloneBuilder;
        this.networking = networking;
    }

    @Override
    public List<ConfigModelId> handlesElements() {
        return configModelIds;
    }

    @Override
    public void doBuild(ContainerModel model, Element spec, ConfigModelContext modelContext) {
        app = modelContext.getApplicationPackage();
        checkVersion(spec);

        this.log = modelContext.getDeployLogger();

        ContainerCluster cluster = createContainerCluster(spec, modelContext);
        addClusterContent(cluster, spec, modelContext);
        addBundlesForPlatformComponents(cluster);

        model.setCluster(cluster);
    }

    protected void addBundlesForPlatformComponents(ContainerCluster cluster) {
        for (Component<?, ?> component : cluster.getAllComponents()) {
            String componentClass = component.model.bundleInstantiationSpec.getClassName();
            BundleMapper.getBundlePath(componentClass).
                    ifPresent(cluster::addPlatformBundle);
        }
    }

    private ContainerCluster createContainerCluster(Element spec, final ConfigModelContext modelContext) {
        return new VespaDomBuilder.DomConfigProducerBuilder<ContainerCluster>() {
            @Override
            protected ContainerCluster doBuild(AbstractConfigProducer ancestor, Element producerSpec) {
                return new ContainerCluster(ancestor, modelContext.getProducerId(), modelContext.getProducerId());
            }
        }.build(modelContext.getParentProducer(), spec);
    }

    private void addClusterContent(ContainerCluster cluster, Element spec, ConfigModelContext modelContext) {
        DocumentFactoryBuilder.buildDocumentFactories(cluster, spec);

        addConfiguredComponents(cluster, spec);
        addHandlers(cluster, spec);

        addHttp(spec, cluster);
        addRestApis(spec, cluster);
        addServlets(spec, cluster);
        addProcessing(spec, cluster);
        addSearch(spec, cluster, modelContext.getDeployState().getQueryProfiles(), modelContext.getDeployState().getSemanticRules());
        addDocproc(spec, cluster);
        addDocumentApi(spec, cluster);  //NOTE: Document API must be set up _after_ search!

        addAccessLogs(cluster, spec);
        addRoutingAliases(cluster, spec);
        addNodes(cluster, spec);

        addClientProviders(spec, cluster);
        addServerProviders(spec, cluster);
        addLegacyFilters(spec, cluster);

        addDefaultHandlers(cluster);
        addStatusHandlers(cluster, modelContext);
        addDefaultComponents(cluster);
        setDefaultMetricConsumerFactory(cluster);

        //TODO: overview handler, see DomQrserverClusterBuilder
        //TODO: cache options.
    }

    private void addRoutingAliases(ContainerCluster cluster, Element spec) {
        Element aliases = XML.getChild(spec, "aliases");
        for (Element alias : XML.getChildren(aliases, "service-alias")) {
            cluster.serviceAliases().add(XML.getValue(alias));
        }
        for (Element alias : XML.getChildren(aliases, "endpoint-alias")) {
            cluster.endpointAliases().add(XML.getValue(alias));
        }
    }

    private void addConfiguredComponents(ContainerCluster cluster, Element spec) {
        for (Element components : XML.getChildren(spec, "components")) {
            addIncludes(components);
            addConfiguredComponents(cluster, components, "component");
        }
        addConfiguredComponents(cluster, spec, "component");
    }

    protected void addDefaultComponents(ContainerCluster cluster) {
    }

    protected void setDefaultMetricConsumerFactory(ContainerCluster cluster) {
        cluster.setDefaultMetricConsumerFactory(MetricDefaultsConfig.Factory.Enum.STATE_MONITOR);
    }

    protected void addDefaultHandlers(ContainerCluster cluster) {
        addDefaultHandlersExceptStatus(cluster);
    }

    protected void addStatusHandlers(ContainerCluster cluster, ConfigModelContext configModelContext) {
        if (configModelContext.getDeployState().isHosted()) {
            String name = "status.html";
            Optional<String> statusFile = Optional.ofNullable(System.getenv(HOSTED_VESPA_STATUS_FILE_YINST_SETTING));
            cluster.addComponent(
                    new FileStatusHandlerComponent(name + "-status-handler", statusFile.orElse(HOSTED_VESPA_STATUS_FILE),
                            "http://*/" + name, "https://*/" + name));
        } else {
            cluster.addVipHandler();
        }
    }

    /**
     * Intended for use by legacy builders only.
     * Will be called during building when using ContainerModelBuilder.
     */
    public static void addDefaultHandler_legacyBuilder(ContainerCluster cluster) {
        addDefaultHandlersExceptStatus(cluster);
        cluster.addVipHandler();
    }

    protected static void addDefaultHandlersExceptStatus(ContainerCluster cluster) {
        cluster.addDefaultRootHandler();
        cluster.addMetricStateHandler();
        cluster.addApplicationStatusHandler();
        cluster.addStatisticsHandler();
    }

    private void addClientProviders(Element spec, ContainerCluster cluster) {
        for (Element clientSpec: XML.getChildren(spec, "client")) {
            cluster.addComponent(new DomClientProviderBuilder().build(cluster, clientSpec));
        }
    }

    private void addServerProviders(Element spec, ContainerCluster cluster) {
        addConfiguredComponents(cluster, spec, "server");
    }

    private void addLegacyFilters(Element spec, ContainerCluster cluster) {
        for (Component component : buildLegacyFilters(cluster, spec)) {
            cluster.addComponent(component);
        }
    }

    private List<Component> buildLegacyFilters(AbstractConfigProducer ancestor,
                                               Element spec) {
        List<Component> components = new ArrayList<>();

        for (Element node : XML.getChildren(spec, "filter")) {
            components.add(new DomFilterBuilder().build(ancestor, node));
        }
        return components;
    }

    protected void addAccessLogs(ContainerCluster cluster, Element spec) {
        List<Element> accessLogElements = getAccessLogElements(spec);

        for (Element accessLog : accessLogElements) {
            AccessLogBuilder.buildIfNotDisabled(cluster, accessLog).ifPresent(cluster::addComponent);
        }

        if (accessLogElements.isEmpty() && cluster.getSearch() != null)
            cluster.addDefaultSearchAccessLog();
    }

    protected final List<Element> getAccessLogElements(Element spec) {
        return XML.getChildren(spec, "accesslog");
    }


    protected void addHttp(Element spec, ContainerCluster cluster) {
        Element httpElement = XML.getChild(spec, "http");
        if (httpElement != null) {
            cluster.setHttp(buildHttp(cluster, httpElement));
        }
    }

    private Http buildHttp(ContainerCluster cluster, Element httpElement) {
        Http http = new HttpBuilder().build(cluster, httpElement);

        if (networking == Networking.disable)
            http.removeAllServers();

        return http;
    }

    protected void addRestApis(Element spec, ContainerCluster cluster) {
        for (Element restApiElem : XML.getChildren(spec, "rest-api")) {
            cluster.addRestApi(
                    new RestApiBuilder().build(cluster, restApiElem));
        }
    }

    private void addServlets(Element spec, ContainerCluster cluster) {
        for (Element servletElem : XML.getChildren(spec, "servlet")) {
            cluster.addServlet(
                    new ServletBuilder().build(cluster, servletElem));
        }
    }

    private void addDocumentApi(Element spec, ContainerCluster cluster) {
        ContainerDocumentApi containerDocumentApi = buildDocumentApi(cluster, spec);
        if (containerDocumentApi != null) {
            cluster.setDocumentApi(containerDocumentApi);
        }
    }

    private void addDocproc(Element spec, ContainerCluster cluster) {
        ContainerDocproc containerDocproc = buildDocproc(cluster, spec);
        if (containerDocproc != null) {
            cluster.setDocproc(containerDocproc);

            ContainerDocproc.Options docprocOptions = containerDocproc.options;
            cluster.setMbusParams(new ContainerCluster.MbusParams(
                    docprocOptions.maxConcurrentFactor, docprocOptions.documentExpansionFactor, docprocOptions.containerCoreMemory));
        }
    }

    private void addSearch(Element spec, ContainerCluster cluster, QueryProfiles queryProfiles, SemanticRules semanticRules) {
        Element searchElement = XML.getChild(spec, "search");
        if (searchElement != null) {
            addIncludes(searchElement);
            cluster.setSearch(buildSearch(cluster, searchElement, queryProfiles, semanticRules));

            addSearchHandler(cluster, searchElement);
            validateAndAddConfiguredComponents(cluster, searchElement, "renderer", ContainerModelBuilder::validateRendererElement);
        }
    }

    private void addProcessing(Element spec, ContainerCluster cluster) {
        Element processingElement = XML.getChild(spec, "processing");
        if (processingElement != null) {
            addIncludes(processingElement);
            cluster.setProcessingChains(new DomProcessingBuilder(null).build(cluster, processingElement),
                    serverBindings(processingElement, ProcessingChains.defaultBindings));
            validateAndAddConfiguredComponents(cluster, processingElement, "renderer", ContainerModelBuilder::validateRendererElement);
        }
    }

    private ContainerSearch buildSearch(ContainerCluster containerCluster, Element producerSpec,
                                        QueryProfiles queryProfiles, SemanticRules semanticRules) {
        SearchChains searchChains = new DomSearchChainsBuilder(null, false).build(containerCluster, producerSpec);

        ContainerSearch containerSearch = new ContainerSearch(containerCluster, searchChains, new ContainerSearch.Options());

        applyApplicationPackageDirectoryConfigs(containerCluster.getRoot().getDeployState().getApplicationPackage(), containerSearch);
        containerSearch.setQueryProfiles(queryProfiles);
        containerSearch.setSemanticRules(semanticRules);

        return containerSearch;
    }

    private void applyApplicationPackageDirectoryConfigs(ApplicationPackage applicationPackage,ContainerSearch containerSearch) {
        PageTemplates.validate(applicationPackage);
        containerSearch.setPageTemplates(PageTemplates.create(applicationPackage));
    }

    private void addHandlers(ContainerCluster cluster, Element spec) {
        for (Element component: XML.getChildren(spec, "handler")) {
            cluster.addComponent(
                    new DomHandlerBuilder().build(cluster, component));
        }
    }

    private void checkVersion(Element spec) {
        String version = spec.getAttribute("version");

        if (!Version.fromString(version).equals(new Version(1))) {
            throw new RuntimeException("Expected container version to be 1.0, but got " + version);
        }
    }

    private void addNodes(ContainerCluster cluster, Element spec) {
        if (standaloneBuilder) {
            addStandaloneNode(cluster);
        } else {
            addNodesFromXml(cluster, spec);
        }
    }

    private void addStandaloneNode(ContainerCluster cluster) {
        Container container =  new Container(cluster, "standalone", cluster.getContainers().size());
        cluster.addContainers(Collections.singleton(container));
    }

    private void addNodesFromXml(ContainerCluster cluster, Element spec) {
        Element nodesElement = XML.getChild(spec, "nodes");
        if (nodesElement == null) { // default single node on localhost
            Container container = new Container(cluster, "container.0", 0);
            HostResource host = allocateSingleNodeHost(cluster, log);
            container.setHostResource(host);
            if ( ! container.isInitialized() ) // TODO: Fold this into initService
                container.initService();
            cluster.addContainers(Collections.singleton(container));
        }
        else {
            String defaultJvmArgs = nodesElement.getAttribute(VespaDomBuilder.JVMARGS_ATTRIB_NAME);
            String defaultPreLoad = null;
            if (nodesElement.hasAttribute(VespaDomBuilder.PRELOAD_ATTRIB_NAME)) {
                defaultPreLoad = nodesElement.getAttribute(VespaDomBuilder.PRELOAD_ATTRIB_NAME);
            }
            boolean useCpuSocketAffinity = false;
            if (nodesElement.hasAttribute(VespaDomBuilder.CPU_SOCKET_AFFINITY_ATTRIB_NAME)) {
                useCpuSocketAffinity = Boolean.parseBoolean(nodesElement.getAttribute(VespaDomBuilder.CPU_SOCKET_AFFINITY_ATTRIB_NAME));
            }
            List<Container> result = new ArrayList<>();
            result.addAll(createNodesFromXmlNodeCount(cluster, nodesElement));
            addNodesFromXmlNodeList(cluster, spec, nodesElement, result);
            applyDefaultJvmArgs(result, defaultJvmArgs);
            applyRoutingAliasProperties(result, cluster);
            if (defaultPreLoad != null) {
                applyDefaultPreload(result, defaultPreLoad);
            }
            if (useCpuSocketAffinity) {
                AbstractService.distributeCpuSocketAffinity(result);
            }

            cluster.addContainers(result);
        }
    }

    private void applyRoutingAliasProperties(List<Container> result, ContainerCluster cluster) {
        if (!cluster.serviceAliases().isEmpty()) {
            result.forEach(container -> {
                container.setProp("servicealiases", cluster.serviceAliases().stream().collect(Collectors.joining(",")));
            });
        }
        if (!cluster.endpointAliases().isEmpty()) {
            result.forEach(container -> {
                container.setProp("endpointaliases", cluster.endpointAliases().stream().collect(Collectors.joining(",")));
            });
        }
    }
    
    private HostResource allocateSingleNodeHost(ContainerCluster cluster, DeployLogger logger) {
        if (cluster.isHostedVespa()) {
            ClusterSpec clusterSpec = ClusterSpec.from(ClusterSpec.Type.container, ClusterSpec.Id.from(cluster.getName()), Optional.empty());
            return cluster.getHostSystem().allocateHosts(clusterSpec, Capacity.fromNodeCount(1), 1, logger).keySet().iterator().next();
        } else {
            return cluster.getHostSystem().getHost(Container.SINGLENODE_CONTAINER_SERVICESPEC);
        }
    }

    private void addNodesFromXmlNodeList(ContainerCluster cluster,
            Element spec, Element nodesElement, List<Container> result) {
        int nodeCount = 0;
        for (Element nodeElem: XML.getChildren(nodesElement, "node")) {
            Container container = new ContainerServiceBuilder("container." + nodeCount, nodeCount).build(cluster, nodeElem);
            result.add(container);
            ++nodeCount;
        }
    }

    private List<Container> createNodesFromXmlNodeCount(ContainerCluster cluster, Element nodesElement) {
        List<Container> result = new ArrayList<>();
        if (nodesElement.hasAttribute("count")) {
            NodesSpecification nodesSpecification = NodesSpecification.from(new ModelElement(nodesElement));
            Map<HostResource, ClusterMembership> hosts = nodesSpecification.provision(cluster.getRoot().getHostSystem(), ClusterSpec.Type.container, ClusterSpec.Id.from(cluster.getName()), Optional.empty(), log);
            for (Map.Entry<HostResource, ClusterMembership> entry : hosts.entrySet()) {
                String id = "container." + entry.getValue().index();
                Container container = new Container(cluster, id, entry.getValue().retired(), entry.getValue().index());
                container.setHostResource(entry.getKey());
                container.initService();
                result.add(container);
            }
        }
        return result;
    }

    private void applyDefaultJvmArgs(List<Container> containers, String defaultJvmArgs) {
        for (Container container: containers) {
            if (container.getJvmArgs().isEmpty())
                container.prependJvmArgs(defaultJvmArgs);
        }
    }

    private void applyDefaultPreload(List<Container> containers, String defaultPreLoad) {
        for (Container container: containers) {
            container.setPreLoad(defaultPreLoad);
        }
    }

    private void addSearchHandler(ContainerCluster cluster, Element searchElement) {
        ProcessingHandler<SearchChains> searchHandler = new ProcessingHandler<>(
                cluster.getSearch().getChains(), "com.yahoo.search.handler.SearchHandler");

        String[] defaultBindings = {"http://*/search/*", "https://*/search/*"};
        for (String binding: serverBindings(searchElement, defaultBindings)) {
            searchHandler.addServerBindings(binding);
        }

        cluster.addComponent(searchHandler);
    }

    private String[] serverBindings(Element searchElement, String... defaultBindings) {
        List<Element> bindings = XML.getChildren(searchElement, "binding");
        if (bindings.isEmpty())
            return defaultBindings;

        return toBindingList(bindings);
    }

    private String[] toBindingList(List<Element> bindingElements) {
        List<String> result = new ArrayList<>();

        for (Element element: bindingElements) {
            String text = element.getTextContent().trim();
            if (!text.isEmpty())
                result.add(text);
        }

        return result.toArray(new String[result.size()]);
    }

    private ContainerDocumentApi buildDocumentApi(ContainerCluster cluster, Element spec) {
        Element documentApiElement = XML.getChild(spec, "document-api");
        if (documentApiElement == null) {
            return null;
        }

        ContainerDocumentApi.Options documentApiOptions = DocumentApiOptionsBuilder.build(documentApiElement);
        return new ContainerDocumentApi(cluster, documentApiOptions);
    }

    private ContainerDocproc buildDocproc(ContainerCluster cluster, Element spec) {
        Element docprocElement = XML.getChild(spec, "document-processing");
        if (docprocElement == null)
            return null;

        addIncludes(docprocElement);
        DocprocChains chains = new DomDocprocChainsBuilder(null, false).build(cluster, docprocElement);

        ContainerDocproc.Options docprocOptions = DocprocOptionsBuilder.build(docprocElement);
        return new ContainerDocproc(cluster, chains, docprocOptions, !standaloneBuilder);
     }

    private void addIncludes(Element parentElement) {
        List<Element> includes = XML.getChildren(parentElement, IncludeDirs.INCLUDE);
        if (includes == null || includes.isEmpty()) {
            return;
        }
        if (app == null) {
            throw new IllegalArgumentException("Element <include> given in XML config, but no application package given.");
        }
        for (Element include : includes) {
            addInclude(parentElement, include);
        }
    }

    private void addInclude(Element parentElement, Element include) {
        String dirName = include.getAttribute(IncludeDirs.DIR);
        app.validateIncludeDir(dirName);

        List<Element> includedFiles = Xml.allElemsFromPath(app, dirName);
        for (Element includedFile : includedFiles) {
            List<Element> includedSubElements = XML.getChildren(includedFile);
            for (Element includedSubElement : includedSubElements) {
                Node copiedNode = parentElement.getOwnerDocument().importNode(includedSubElement, true);
                parentElement.appendChild(copiedNode);
            }
        }
    }

    public static void addConfiguredComponents(ContainerCluster cluster, Element spec, String componentName) {
        for (Element node : XML.getChildren(spec, componentName)) {
            cluster.addComponent(new DomComponentBuilder().build(cluster, node));
        }
    }

    public static void validateAndAddConfiguredComponents(ContainerCluster cluster, Element spec, String componentName, Consumer<Element> elementValidator) {
        for (Element node : XML.getChildren(spec, componentName)) {
            elementValidator.accept(node); // throws exception here if something is wrong
            cluster.addComponent(new DomComponentBuilder().build(cluster, node));
        }
    }

    /**
     * Disallow renderers named "DefaultRenderer" or "JsonRenderer"
     */
    private static void validateRendererElement(Element element) {
        String idAttr = element.getAttribute("id");

        if (idAttr.equals(xmlRendererId) || idAttr.equals(jsonRendererId)) {
            throw new IllegalArgumentException(String.format("Renderer id %s is reserved for internal use", idAttr));
        }
    }
}
