// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.xml;

import com.google.common.collect.ImmutableList;
import com.yahoo.component.Version;
import com.yahoo.config.application.Xml;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.model.ConfigModelContext;
import com.yahoo.config.model.api.ConfigServerSpec;
import com.yahoo.config.model.application.provider.IncludeDirs;
import com.yahoo.config.model.builder.xml.ConfigModelBuilder;
import com.yahoo.config.model.builder.xml.ConfigModelId;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.config.provision.AthenzService;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Rotation;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.config.MetricDefaultsConfig;
import com.yahoo.search.rendering.RendererRegistry;
import com.yahoo.searchdefinition.derived.RankProfileList;
import com.yahoo.text.XML;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.model.AbstractService;
import com.yahoo.vespa.model.HostResource;
import com.yahoo.vespa.model.HostSystem;
import com.yahoo.vespa.model.builder.xml.dom.DomClientProviderBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomComponentBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomFilterBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomHandlerBuilder;
import com.yahoo.vespa.model.builder.xml.dom.ModelElement;
import com.yahoo.vespa.model.builder.xml.dom.NodesSpecification;
import com.yahoo.vespa.model.builder.xml.dom.ServletBuilder;
import com.yahoo.vespa.model.builder.xml.dom.VespaDomBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.docproc.DomDocprocChainsBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.processing.DomProcessingBuilder;
import com.yahoo.vespa.model.builder.xml.dom.chains.search.DomSearchChainsBuilder;
import com.yahoo.vespa.model.clients.ContainerDocumentApi;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.ContainerCluster;
import com.yahoo.vespa.model.container.ContainerModel;
import com.yahoo.vespa.model.container.ContainerModelEvaluation;
import com.yahoo.vespa.model.container.IdentityProvider;
import com.yahoo.vespa.model.container.SecretStore;
import com.yahoo.vespa.model.container.component.Component;
import com.yahoo.vespa.model.container.component.FileStatusHandlerComponent;
import com.yahoo.vespa.model.container.component.Handler;
import com.yahoo.vespa.model.container.component.chain.ProcessingHandler;
import com.yahoo.vespa.model.container.docproc.ContainerDocproc;
import com.yahoo.vespa.model.container.docproc.DocprocChains;
import com.yahoo.vespa.model.container.http.Http;
import com.yahoo.vespa.model.container.http.xml.HttpBuilder;
import com.yahoo.vespa.model.container.jersey.xml.RestApiBuilder;
import com.yahoo.vespa.model.container.processing.ProcessingChains;
import com.yahoo.vespa.model.container.search.ContainerSearch;
import com.yahoo.vespa.model.container.search.GUIHandler;
import com.yahoo.vespa.model.container.search.PageTemplates;
import com.yahoo.vespa.model.container.search.searchchain.SearchChains;
import com.yahoo.vespa.model.container.xml.document.DocumentFactoryBuilder;
import com.yahoo.vespa.model.content.StorageGroup;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Tony Vaagenes
 * @author gjoranv
 */
public class ContainerModelBuilder extends ConfigModelBuilder<ContainerModel> {

    /**
     * Default path to vip status file for container in Hosted Vespa.
     */
    static final String HOSTED_VESPA_STATUS_FILE = Defaults.getDefaults().underVespaHome("var/mediasearch/oor/status.html");
    /**
     * Path to vip status file for container in Hosted Vespa. Only used if set, else use HOSTED_VESPA_STATUS_FILE
     */
    private static final String HOSTED_VESPA_STATUS_FILE_INSTALL_SETTING = "cloudconfig_server__tenant_vip_status_file";

    public enum Networking { disable, enable }

    private ApplicationPackage app;
    private final boolean standaloneBuilder;
    private final Networking networking;
    private final boolean rpcServerEnabled;
    private final boolean httpServerEnabled;
    protected DeployLogger log;

    public static final List<ConfigModelId> configModelIds =  
            ImmutableList.of(ConfigModelId.fromName("container"), ConfigModelId.fromName("jdisc"));

    private static final String xmlRendererId = RendererRegistry.xmlRendererId.getName();
    private static final String jsonRendererId = RendererRegistry.jsonRendererId.getName();

    public ContainerModelBuilder(boolean standaloneBuilder, Networking networking) {
        super(ContainerModel.class);
        this.standaloneBuilder = standaloneBuilder;
        this.networking = networking;
        // Always disable rpc server for standalone container
        this.rpcServerEnabled = !standaloneBuilder;
        this.httpServerEnabled = networking == Networking.enable;
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
        cluster.setRpcServerEnabled(rpcServerEnabled);
        cluster.setHttpServerEnabled(httpServerEnabled);
        model.setCluster(cluster);
    }

    private void addBundlesForPlatformComponents(ContainerCluster cluster) {
        for (Component<?, ?> component : cluster.getAllComponents()) {
            String componentClass = component.model.bundleInstantiationSpec.getClassName();
            BundleMapper.getBundlePath(componentClass).
                    ifPresent(cluster::addPlatformBundle);
        }
    }

    private ContainerCluster createContainerCluster(Element spec, ConfigModelContext modelContext) {
        return new VespaDomBuilder.DomConfigProducerBuilder<ContainerCluster>() {
            @Override
            protected ContainerCluster doBuild(DeployState deployState, AbstractConfigProducer ancestor, Element producerSpec) {
                return new ContainerCluster(ancestor, modelContext.getProducerId(),
                                            modelContext.getProducerId(), deployState);
            }
        }.build(modelContext.getDeployState(), modelContext.getParentProducer(), spec);
    }

    private void addClusterContent(ContainerCluster cluster, Element spec, ConfigModelContext context) {
        DeployState deployState = context.getDeployState();
        DocumentFactoryBuilder.buildDocumentFactories(cluster, spec);
        addConfiguredComponents(deployState, cluster, spec);
        addSecretStore(cluster, spec);
        addHandlers(deployState, cluster, spec);
        addRestApis(deployState, spec, cluster);
        addServlets(deployState, spec, cluster);
        addProcessing(deployState, spec, cluster);
        addSearch(deployState, spec, cluster);
        addModelEvaluation(spec, cluster, context);
        addDocproc(deployState, spec, cluster);
        addDocumentApi(spec, cluster);  // NOTE: Must be done after addSearch

        addDefaultHandlers(cluster);
        addStatusHandlers(cluster, context);
        setDefaultMetricConsumerFactory(cluster);

        addHttp(deployState, spec, cluster);

        addAccessLogs(deployState, cluster, spec);
        addRoutingAliases(cluster, spec, deployState.zone().environment());
        addNodes(cluster, spec, context);

        addClientProviders(deployState, spec, cluster);
        addServerProviders(deployState, spec, cluster);

        addAthensCopperArgos(cluster, context);  // Must be added after nodes.
    }

    private void addSecretStore(ContainerCluster cluster, Element spec) {
        Element secretStoreElement = XML.getChild(spec, "secret-store");
        if (secretStoreElement != null) {
            SecretStore secretStore = new SecretStore();
            for (Element group : XML.getChildren(secretStoreElement, "group")) {
                secretStore.addGroup(group.getAttribute("name"), group.getAttribute("environment"));
            }
            cluster.setSecretStore(secretStore);
        }
    }

    private void addAthensCopperArgos(ContainerCluster cluster, ConfigModelContext context) {
        app.getDeployment().map(DeploymentSpec::fromXml)
                .ifPresent(deploymentSpec -> {
                    addIdentityProvider(cluster,
                                        context.getDeployState().getProperties().configServerSpecs(),
                                        context.getDeployState().getProperties().loadBalancerName(),
                                        context.getDeployState().getProperties().ztsUrl(),
                                        context.getDeployState().getProperties().athenzDnsSuffix(),
                                        context.getDeployState().zone(),
                                        deploymentSpec);
                    addRotationProperties(cluster, context.getDeployState().zone(), context.getDeployState().getRotations(), deploymentSpec);
                });
    }

    private void addRotationProperties(ContainerCluster cluster, Zone zone, Set<Rotation> rotations, DeploymentSpec spec) {
        cluster.getContainers().forEach(container -> {
            setRotations(container, rotations, spec.globalServiceId(), cluster.getName());
            container.setProp("activeRotation", Boolean.toString(zoneHasActiveRotation(zone, spec)));
        });
    }

    private boolean zoneHasActiveRotation(Zone zone, DeploymentSpec spec) {
        return spec.zones().stream()
                .anyMatch(declaredZone -> declaredZone.deploysTo(zone.environment(), Optional.of(zone.region())) &&
                                                     declaredZone.active());
    }

    private void setRotations(Container container, Set<Rotation> rotations, Optional<String> globalServiceId, String containerClusterName) {

        if ( ! rotations.isEmpty() && globalServiceId.isPresent()) {
            if (containerClusterName.equals(globalServiceId.get())) {
                container.setProp("rotations", rotations.stream().map(Rotation::getId).collect(Collectors.joining(",")));
            }
        }
    }

    private void addRoutingAliases(ContainerCluster cluster, Element spec, Environment environment) {
        if (environment != Environment.prod) return;

        Element aliases = XML.getChild(spec, "aliases");
        for (Element alias : XML.getChildren(aliases, "service-alias")) {
            cluster.serviceAliases().add(XML.getValue(alias));
        }
        for (Element alias : XML.getChildren(aliases, "endpoint-alias")) {
            cluster.endpointAliases().add(XML.getValue(alias));
        }
    }

    private void addConfiguredComponents(DeployState deployState, ContainerCluster cluster, Element spec) {
        for (Element components : XML.getChildren(spec, "components")) {
            addIncludes(components);
            addConfiguredComponents(deployState, cluster, components, "component");
        }
        addConfiguredComponents(deployState, cluster, spec, "component");
    }

    private void setDefaultMetricConsumerFactory(ContainerCluster cluster) {
        cluster.setDefaultMetricConsumerFactory(MetricDefaultsConfig.Factory.Enum.STATE_MONITOR);
    }

    private void addDefaultHandlers(ContainerCluster cluster) {
        addDefaultHandlersExceptStatus(cluster);
    }

    private void addStatusHandlers(ContainerCluster cluster, ConfigModelContext configModelContext) {
        if (configModelContext.getDeployState().isHosted()) {
            String name = "status.html";
            Optional<String> statusFile = Optional.ofNullable(System.getenv(HOSTED_VESPA_STATUS_FILE_INSTALL_SETTING));
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

    private static void addDefaultHandlersExceptStatus(ContainerCluster cluster) {
        cluster.addDefaultRootHandler();
        cluster.addMetricStateHandler();
        cluster.addApplicationStatusHandler();
        cluster.addStatisticsHandler();
    }

    private void addClientProviders(DeployState deployState, Element spec, ContainerCluster cluster) {
        for (Element clientSpec: XML.getChildren(spec, "client")) {
            cluster.addComponent(new DomClientProviderBuilder().build(deployState, cluster, clientSpec));
        }
    }

    private void addServerProviders(DeployState deployState, Element spec, ContainerCluster cluster) {
        addConfiguredComponents(deployState, cluster, spec, "server");
    }

    private void addAccessLogs(DeployState deployState, ContainerCluster cluster, Element spec) {
        List<Element> accessLogElements = getAccessLogElements(spec);

        for (Element accessLog : accessLogElements) {
            AccessLogBuilder.buildIfNotDisabled(deployState, cluster, accessLog).ifPresent(cluster::addComponent);
        }

        if (accessLogElements.isEmpty() && cluster.getSearch() != null)
            cluster.addDefaultSearchAccessLog();
    }

    private List<Element> getAccessLogElements(Element spec) {
        return XML.getChildren(spec, "accesslog");
    }


    private void addHttp(DeployState deployState, Element spec, ContainerCluster cluster) {
        Element httpElement = XML.getChild(spec, "http");
        if (httpElement != null) {
            cluster.setHttp(buildHttp(deployState, cluster, httpElement));
        }
    }

    private Http buildHttp(DeployState deployState, ContainerCluster cluster, Element httpElement) {
        Http http = new HttpBuilder().build(deployState, cluster, httpElement);

        if (networking == Networking.disable)
            http.removeAllServers();

        return http;
    }

    private void addRestApis(DeployState deployState, Element spec, ContainerCluster cluster) {
        for (Element restApiElem : XML.getChildren(spec, "rest-api")) {
            cluster.addRestApi(
                    new RestApiBuilder().build(deployState, cluster, restApiElem));
        }
    }

    private void addServlets(DeployState deployState, Element spec, ContainerCluster cluster) {
        for (Element servletElem : XML.getChildren(spec, "servlet"))
            cluster.addServlet(new ServletBuilder().build(deployState, cluster, servletElem));
    }

    private void addDocumentApi(Element spec, ContainerCluster cluster) {
        ContainerDocumentApi containerDocumentApi = buildDocumentApi(cluster, spec);
        if (containerDocumentApi == null) return;

        cluster.setDocumentApi(containerDocumentApi);
    }

    private void addDocproc(DeployState deployState, Element spec, ContainerCluster cluster) {
        ContainerDocproc containerDocproc = buildDocproc(deployState, cluster, spec);
        if (containerDocproc == null) return;
        cluster.setDocproc(containerDocproc);

        ContainerDocproc.Options docprocOptions = containerDocproc.options;
        cluster.setMbusParams(new ContainerCluster.MbusParams(
                docprocOptions.maxConcurrentFactor, docprocOptions.documentExpansionFactor, docprocOptions.containerCoreMemory));
    }

    private void addSearch(DeployState deployState, Element spec, ContainerCluster cluster) {
        Element searchElement = XML.getChild(spec, "search");
        if (searchElement == null) return;

        addIncludes(searchElement);
        cluster.setSearch(buildSearch(deployState, cluster, searchElement));

        addSearchHandler(cluster, searchElement);
        addGUIHandler(cluster);
        validateAndAddConfiguredComponents(deployState, cluster, searchElement, "renderer", ContainerModelBuilder::validateRendererElement);
    }

    private void addModelEvaluation(Element spec, ContainerCluster cluster, ConfigModelContext context) {
        Element modelEvaluationElement = XML.getChild(spec, "model-evaluation");
        if (modelEvaluationElement == null) return;

        RankProfileList profiles =
                context.vespaModel() != null ? context.vespaModel().rankProfileList() : RankProfileList.empty;
        cluster.setModelEvaluation(new ContainerModelEvaluation(cluster, profiles));
    }

    private void addProcessing(DeployState deployState, Element spec, ContainerCluster cluster) {
        Element processingElement = XML.getChild(spec, "processing");
        if (processingElement == null) return;

        addIncludes(processingElement);
        cluster.setProcessingChains(new DomProcessingBuilder(null).build(deployState, cluster, processingElement),
                                    serverBindings(processingElement, ProcessingChains.defaultBindings));
        validateAndAddConfiguredComponents(deployState, cluster, processingElement, "renderer", ContainerModelBuilder::validateRendererElement);
    }

    private ContainerSearch buildSearch(DeployState deployState, ContainerCluster containerCluster, Element producerSpec) {
        SearchChains searchChains = new DomSearchChainsBuilder(null, false).build(deployState, containerCluster, producerSpec);

        ContainerSearch containerSearch = new ContainerSearch(containerCluster, searchChains, new ContainerSearch.Options());

        applyApplicationPackageDirectoryConfigs(deployState.getApplicationPackage(), containerSearch);
        containerSearch.setQueryProfiles(deployState.getQueryProfiles());
        containerSearch.setSemanticRules(deployState.getSemanticRules());

        return containerSearch;
    }

    private void applyApplicationPackageDirectoryConfigs(ApplicationPackage applicationPackage,ContainerSearch containerSearch) {
        PageTemplates.validate(applicationPackage);
        containerSearch.setPageTemplates(PageTemplates.create(applicationPackage));
    }

    private void addHandlers(DeployState deployState, ContainerCluster cluster, Element spec) {
        for (Element component: XML.getChildren(spec, "handler")) {
            cluster.addComponent(
                    new DomHandlerBuilder().build(deployState, cluster, component));
        }
    }

    private void checkVersion(Element spec) {
        String version = spec.getAttribute("version");

        if ( ! Version.fromString(version).equals(new Version(1))) {
            throw new RuntimeException("Expected container version to be 1.0, but got " + version);
        }
    }

    private void addNodes(ContainerCluster cluster, Element spec, ConfigModelContext context) {
        if (standaloneBuilder)
            addStandaloneNode(cluster);
        else
            addNodesFromXml(cluster, spec, context);
    }

    private void addStandaloneNode(ContainerCluster cluster) {
        Container container =  new Container(cluster, "standalone", cluster.getContainers().size(), cluster.isHostedVespa());
        cluster.addContainers(Collections.singleton(container));
    }

    static boolean incompatibleGCOptions(String jvmargs) {
        Pattern gcAlgorithm = Pattern.compile("-XX:[-+]Use.+GC");
        Pattern cmsArgs = Pattern.compile("-XX:[-+]*CMS");
        return (gcAlgorithm.matcher(jvmargs).find() ||cmsArgs.matcher(jvmargs).find());
    }

    private static String buildJvmGCOptions(Zone zone, String jvmGCOPtions, boolean isHostedVespa) {
        if (jvmGCOPtions != null) {
            return jvmGCOPtions;
        } else if (zone.system() == SystemName.dev) {
            return ContainerCluster.G1GC;
        } else if (isHostedVespa) {
            return ((zone.environment() != Environment.prod) || RegionName.from("us-east-3").equals(zone.region()))
                    ? ContainerCluster.G1GC : ContainerCluster.CMS;
        } else {
            return ContainerCluster.G1GC;
        }
    }
    private void addNodesFromXml(ContainerCluster cluster, Element containerElement, ConfigModelContext context) {
        Element nodesElement = XML.getChild(containerElement, "nodes");
        if (nodesElement == null) { // default single node on localhost
            Container node = new Container(cluster, "container.0", 0, cluster.isHostedVespa());
            HostResource host = allocateSingleNodeHost(cluster, log, containerElement, context);
            node.setHostResource(host);
            node.initService(context.getDeployLogger());
            cluster.addContainers(Collections.singleton(node));
        }
        else {
            List<Container> nodes = createNodes(cluster, nodesElement, context);
            String jvmArgs = nodesElement.getAttribute(VespaDomBuilder.JVMARGS_ATTRIB_NAME);
            String jvmGCOptions = nodesElement.hasAttribute(VespaDomBuilder.JVM_GC_OPTIONS)
                    ? nodesElement.getAttribute(VespaDomBuilder.JVM_GC_OPTIONS)
                    : null;
            if (incompatibleGCOptions(jvmArgs)) {
                context.getDeployLogger().log(Level.WARNING, "You need to move out your GC related options from 'jvmargs' to 'jvm-gc-options'");
                cluster.setJvmGCOptions(ContainerCluster.G1GC);
            } else {
                cluster.setJvmGCOptions(buildJvmGCOptions(context.getDeployState().zone(), jvmGCOptions, context.getDeployState().isHosted()));
            }
            applyNodesTagJvmArgs(nodes, jvmArgs);
            applyRoutingAliasProperties(nodes, cluster);
            applyDefaultPreload(nodes, nodesElement);
            applyMemoryPercentage(cluster, nodesElement.getAttribute(VespaDomBuilder.Allocated_MEMORY_ATTRIB_NAME));
            if (useCpuSocketAffinity(nodesElement))
                AbstractService.distributeCpuSocketAffinity(nodes);

            cluster.addContainers(nodes);
        }
    }
    
    private List<Container> createNodes(ContainerCluster cluster, Element nodesElement, ConfigModelContext context) {
        if (nodesElement.hasAttribute("count")) // regular, hosted node spec
            return createNodesFromNodeCount(cluster, nodesElement, context);
        else if (nodesElement.hasAttribute("type")) // internal use for hosted system infrastructure nodes
            return createNodesFromNodeType(cluster, nodesElement, context);
        else if (nodesElement.hasAttribute("of")) // hosted node spec referencing a content cluster
            return createNodesFromContentServiceReference(cluster, nodesElement, context);
        else // the non-hosted option
            return createNodesFromNodeList(context.getDeployState(), cluster, nodesElement);
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
    
    private void applyMemoryPercentage(ContainerCluster cluster, String memoryPercentage) {
        if (memoryPercentage == null || memoryPercentage.isEmpty()) return;
        memoryPercentage = memoryPercentage.trim();

        if ( ! memoryPercentage.endsWith("%"))
            throw new IllegalArgumentException("The memory percentage given for nodes in " + cluster +
                                               " must be an integer percentage ending by the '%' sign");
        memoryPercentage = memoryPercentage.substring(0, memoryPercentage.length()-1).trim();

        try {
            cluster.setMemoryPercentage(Integer.parseInt(memoryPercentage));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("The memory percentage given for nodes in " + cluster +
                                               " must be an integer percentage ending by the '%' sign");
        }
    }
    
    /** Creates a single host when there is no nodes tag */
    private HostResource allocateSingleNodeHost(ContainerCluster cluster, DeployLogger logger, Element containerElement, ConfigModelContext context) {
        DeployState deployState = context.getDeployState();
        HostSystem hostSystem = cluster.getHostSystem();
        if (deployState.isHosted()) {
            Optional<HostResource> singleContentHost = getHostResourceFromContentClusters(cluster, containerElement, context);
            if (singleContentHost.isPresent()) { // there is a content cluster; put the container on its first node 
                return singleContentHost.get();
            }
            else { // request 1 node
                ClusterSpec clusterSpec = ClusterSpec.request(ClusterSpec.Type.container,
                                                              ClusterSpec.Id.from(cluster.getName()),
                                                              deployState.getWantedNodeVespaVersion(),
                                                              false);
                Capacity capacity = Capacity.fromNodeCount(1,
                                                           Optional.empty(),
                                                           false,
                                                           ! deployState.getProperties().isBootstrap());
                return hostSystem.allocateHosts(clusterSpec, capacity, 1, logger).keySet().iterator().next();
            }
        } else {
            return hostSystem.getHost(Container.SINGLENODE_CONTAINER_SERVICESPEC);
        }
    }

    private List<Container> createNodesFromNodeCount(ContainerCluster cluster, Element nodesElement, ConfigModelContext context) {
        NodesSpecification nodesSpecification = NodesSpecification.from(new ModelElement(nodesElement), context);
        Map<HostResource, ClusterMembership> hosts = nodesSpecification.provision(cluster.getRoot().getHostSystem(),
                                                                                  ClusterSpec.Type.container,
                                                                                  ClusterSpec.Id.from(cluster.getName()), 
                                                                                  log);
        return createNodesFromHosts(context.getDeployLogger(), hosts, cluster);
    }

    private List<Container> createNodesFromNodeType(ContainerCluster cluster, Element nodesElement, ConfigModelContext context) {
        NodeType type = NodeType.valueOf(nodesElement.getAttribute("type"));
        ClusterSpec clusterSpec = ClusterSpec.request(ClusterSpec.Type.container, 
                                                      ClusterSpec.Id.from(cluster.getName()), 
                                                      context.getDeployState().getWantedNodeVespaVersion(),
                                                      false);
        Map<HostResource, ClusterMembership> hosts = 
                cluster.getRoot().getHostSystem().allocateHosts(clusterSpec, 
                                                                Capacity.fromRequiredNodeType(type), 1, log);
        return createNodesFromHosts(context.getDeployLogger(), hosts, cluster);
    }
    
    private List<Container> createNodesFromContentServiceReference(ContainerCluster cluster, Element nodesElement, ConfigModelContext context) {
        // Resolve references to content clusters at the XML level because content clusters must be built after container clusters
        String referenceId = nodesElement.getAttribute("of");
        Element services = servicesRootOf(nodesElement).orElseThrow(() -> clusterReferenceNotFoundException(cluster, referenceId));
        Element referencedService = findChildById(services, referenceId).orElseThrow(() -> clusterReferenceNotFoundException(cluster, referenceId));
        if ( ! referencedService.getTagName().equals("content"))
            throw new IllegalArgumentException(cluster + " references service '" + referenceId + "', " +
                                               "but that is not a content service");
        Element referencedNodesElement = XML.getChild(referencedService, "nodes");
        if (referencedNodesElement == null)
            throw new IllegalArgumentException(cluster + " references service '" + referenceId + "' to supply nodes, " + 
                                               "but that service has no <nodes> element");
        
        cluster.setHostClusterId(referenceId);

        Map<HostResource, ClusterMembership> hosts = 
                StorageGroup.provisionHosts(NodesSpecification.from(new ModelElement(referencedNodesElement), context),
                                            referenceId, 
                                            cluster.getRoot().getHostSystem(),
                                            context.getDeployLogger());
        return createNodesFromHosts(context.getDeployLogger(), hosts, cluster);
    }

    /**
     * This is used in case we are on hosted Vespa and no nodes tag is supplied:
     * If there are content clusters this will pick the first host in the first cluster as the container node.
     * If there are no content clusters this will return empty (such that the node can be created by the container here).
     */
    private Optional<HostResource> getHostResourceFromContentClusters(ContainerCluster cluster, Element containersElement, ConfigModelContext context) {
        Optional<Element> services = servicesRootOf(containersElement);
        if ( ! services.isPresent())
            return Optional.empty();
        List<Element> contentServices = XML.getChildren(services.get(), "content");
        if ( contentServices.isEmpty() ) return Optional.empty();
        Element contentNodesElementOrNull = XML.getChild(contentServices.get(0), "nodes");
        
        NodesSpecification nodesSpec;
        if (contentNodesElementOrNull == null)
            nodesSpec = NodesSpecification.nonDedicated(1, context);
        else
            nodesSpec = NodesSpecification.from(new ModelElement(contentNodesElementOrNull), context);

        Map<HostResource, ClusterMembership> hosts =
                StorageGroup.provisionHosts(nodesSpec,
                                            contentServices.get(0).getAttribute("id"),
                                            cluster.getRoot().getHostSystem(),
                                            context.getDeployLogger());
        return Optional.of(hosts.keySet().iterator().next());
    }

    /** Returns the services element above the given Element, or empty if there is no services element */
    private Optional<Element> servicesRootOf(Element element) {
        Node parent = element.getParentNode();
        if (parent == null) return Optional.empty();
        if ( ! (parent instanceof Element)) return Optional.empty();
        Element parentElement = (Element)parent;
        if (parentElement.getTagName().equals("services")) return Optional.of(parentElement);
        return servicesRootOf(parentElement);
    }
    
    private List<Container> createNodesFromHosts(DeployLogger deployLogger, Map<HostResource, ClusterMembership> hosts, ContainerCluster cluster) {
        List<Container> nodes = new ArrayList<>();
        for (Map.Entry<HostResource, ClusterMembership> entry : hosts.entrySet()) {
            String id = "container." + entry.getValue().index();
            Container container = new Container(cluster, id, entry.getValue().retired(), entry.getValue().index(), cluster.isHostedVespa());
            container.setHostResource(entry.getKey());
            container.initService(deployLogger);
            nodes.add(container);
        }
        return nodes;
    }

    private List<Container> createNodesFromNodeList(DeployState deployState, ContainerCluster cluster, Element nodesElement) {
        List<Container> nodes = new ArrayList<>();
        int nodeIndex = 0;
        for (Element nodeElem: XML.getChildren(nodesElement, "node")) {
            nodes.add(new ContainerServiceBuilder("container." + nodeIndex, nodeIndex).build(deployState, cluster, nodeElem));
            nodeIndex++;
        }
        return nodes;
    }

    private IllegalArgumentException clusterReferenceNotFoundException(ContainerCluster cluster, String referenceId) {
        return new IllegalArgumentException(cluster + " references service '" + referenceId +
                                            "' but this service is not defined");
    }

    private Optional<Element> findChildById(Element parent, String id) {
        for (Element child : XML.getChildren(parent))
            if (id.equals(child.getAttribute("id"))) return Optional.of(child);
        return Optional.empty();
    }

    private boolean useCpuSocketAffinity(Element nodesElement) {
        if (nodesElement.hasAttribute(VespaDomBuilder.CPU_SOCKET_AFFINITY_ATTRIB_NAME))
            return Boolean.parseBoolean(nodesElement.getAttribute(VespaDomBuilder.CPU_SOCKET_AFFINITY_ATTRIB_NAME));
        else
            return false;
    }

    private void applyNodesTagJvmArgs(List<Container> containers, String jvmArgs) {
        for (Container container: containers) {
            if (container.getAssignedJvmArgs().isEmpty())
                container.prependJvmArgs(jvmArgs);
        }
    }

    private void applyDefaultPreload(List<Container> containers, Element nodesElement) {
        if (! nodesElement.hasAttribute(VespaDomBuilder.PRELOAD_ATTRIB_NAME)) return;
        for (Container container: containers)
            container.setPreLoad(nodesElement.getAttribute(VespaDomBuilder.PRELOAD_ATTRIB_NAME));
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

    private void addGUIHandler(ContainerCluster cluster) {
        Handler<?> guiHandler = new GUIHandler();
        guiHandler.addServerBindings("http://"+GUIHandler.BINDING, "https://"+GUIHandler.BINDING);
        cluster.addComponent(guiHandler);
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
        if (documentApiElement == null) return null;

        ContainerDocumentApi.Options documentApiOptions = DocumentApiOptionsBuilder.build(documentApiElement);
        return new ContainerDocumentApi(cluster, documentApiOptions);
    }

    private ContainerDocproc buildDocproc(DeployState deployState, ContainerCluster cluster, Element spec) {
        Element docprocElement = XML.getChild(spec, "document-processing");
        if (docprocElement == null)
            return null;

        addIncludes(docprocElement);
        DocprocChains chains = new DomDocprocChainsBuilder(null, false).build(deployState, cluster, docprocElement);

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

    private static void addConfiguredComponents(DeployState deployState, ContainerCluster cluster, Element spec, String componentName) {
        for (Element node : XML.getChildren(spec, componentName)) {
            cluster.addComponent(new DomComponentBuilder().build(deployState, cluster, node));
        }
    }

    private static void validateAndAddConfiguredComponents(DeployState deployState, ContainerCluster cluster, Element spec, String componentName, Consumer<Element> elementValidator) {
        for (Element node : XML.getChildren(spec, componentName)) {
            elementValidator.accept(node); // throws exception here if something is wrong
            cluster.addComponent(new DomComponentBuilder().build(deployState, cluster, node));
        }
    }

    private void addIdentityProvider(ContainerCluster cluster,
                                     List<ConfigServerSpec> configServerSpecs,
                                     HostName loadBalancerName,
                                     URI ztsUrl,
                                     String athenzDnsSuffix,
                                     Zone zone,
                                     DeploymentSpec spec) {
        spec.athenzDomain().ifPresent(domain -> {
            AthenzService service = spec.athenzService(zone.environment(), zone.region())
                    .orElseThrow(() -> new RuntimeException("Missing Athenz service configuration"));
            String zoneDnsSuffix = zone.environment().value() + "-" + zone.region().value() + "." + athenzDnsSuffix;
            IdentityProvider identityProvider = new IdentityProvider(domain, service, getLoadBalancerName(loadBalancerName, configServerSpecs), ztsUrl, zoneDnsSuffix, zone);
            cluster.addComponent(identityProvider);

            cluster.getContainers().forEach(container -> {
                container.setProp("identity.domain", domain.value());
                container.setProp("identity.service", service.value());
            });
        });
    }

    private HostName getLoadBalancerName(HostName loadbalancerName, List<ConfigServerSpec> configServerSpecs) {
        // Set lbaddress, or use first hostname if not specified.
        // TODO: Remove this method and use the loadbalancerName directly
        return Optional.ofNullable(loadbalancerName)
                .orElseGet(
                        () -> HostName.from(configServerSpecs.stream()
                                                    .findFirst()
                                                    .map(ConfigServerSpec::getHostName)
                                                    .orElse("unknown") // Currently unable to test this, hence the unknown
                        ));
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
