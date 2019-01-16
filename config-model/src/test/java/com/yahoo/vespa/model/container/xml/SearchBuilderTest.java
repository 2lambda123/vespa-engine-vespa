// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.xml;

import com.yahoo.config.model.builder.xml.test.DomBuilderTest;
import com.yahoo.container.core.ChainsConfig;
import com.yahoo.container.jdisc.JdiscBindingsConfig;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.ContainerCluster;
import com.yahoo.vespa.model.container.component.Handler;
import com.yahoo.vespa.model.container.search.GUIHandler;
import com.yahoo.vespa.model.test.utils.ApplicationPackageUtils;
import com.yahoo.vespa.model.test.utils.VespaModelCreatorWithMockPkg;
import org.junit.Test;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;

import static com.yahoo.test.Matchers.hasItemWithMethod;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

/**
 * @author gjoranv
 */
public class SearchBuilderTest extends ContainerModelBuilderTestBase {

    private ChainsConfig chainsConfig() {
        return root.getConfig(ChainsConfig.class, "default/component/com.yahoo.search.handler.SearchHandler");
    }

    @Test
    public void gui_search_handler_is_always_included_when_search_is_specified() {
        Element clusterElem = DomBuilderTest.parse(
                "<jdisc id='default' version='1.0'>",
                "  <search />",
                nodesXml,
                "</jdisc>");

        createModel(root, clusterElem);

        String discBindingsConfig = root.getConfig(JdiscBindingsConfig.class, "default").toString();
        assertThat(discBindingsConfig, containsString(GUIHandler.BINDING));

        ContainerCluster cluster = (ContainerCluster)root.getChildren().get("default");

        GUIHandler guiHandler = null;
        for (Handler<?> handler : cluster.getHandlers()) {
            if (handler instanceof GUIHandler) {
                guiHandler = (GUIHandler) handler;
            }
        }
        if (guiHandler == null) fail();
    }



    @Test
    public void search_handler_bindings_can_be_overridden() {
        Element clusterElem = DomBuilderTest.parse(
                "<jdisc id='default' version='1.0'>",
                "  <search>",
                "    <binding>binding0</binding>",
                "    <binding>binding1</binding>",
                "  </search>",
                nodesXml,
                "</jdisc>");

        createModel(root, clusterElem);

        String discBindingsConfig = root.getConfig(JdiscBindingsConfig.class, "default").toString();
        assertThat(discBindingsConfig, containsString(".serverBindings[0] \"binding0\""));
        assertThat(discBindingsConfig, containsString(".serverBindings[1] \"binding1\""));
        assertThat(discBindingsConfig, not(containsString("/search/*")));
    }

    @Test
    public void search_handler_bindings_can_be_disabled() {
        Element clusterElem = DomBuilderTest.parse(
                "<jdisc id='default' version='1.0'>",
                "  <search>",
                "    <binding/>",
                "  </search>",
                nodesXml,
                "</jdisc>");

        createModel(root, clusterElem);

        String discBindingsConfig = root.getConfig(JdiscBindingsConfig.class, "default").toString();
        assertThat(discBindingsConfig, not(containsString("/search/*")));
    }

    // TODO: remove test when all containers are named 'container'
    @Test
    public void cluster_with_only_search_gets_qrserver_as_service_name() throws Exception {
        createClusterWithOnlyDefaultChains();
        ContainerCluster cluster = (ContainerCluster)root.getChildren().get("default");
        assertThat(cluster.getContainers().get(0).getServiceName(), is("qrserver"));
    }

    @Test
    public void empty_search_element_gives_default_chains() throws Exception {
        createClusterWithOnlyDefaultChains();
        assertThat(chainsConfig().chains(), hasItemWithMethod("vespaPhases", "id"));
        assertThat(chainsConfig().chains(), hasItemWithMethod("native", "id"));
        assertThat(chainsConfig().chains(), hasItemWithMethod("vespa", "id"));
    }

    private void createClusterWithOnlyDefaultChains() {
        Element containerElem = DomBuilderTest.parse(
                "<jdisc id='default' version='1.0'>",
                "  <search/>",
                "  <nodes>",
                "    <node hostalias='mockhost' />",
                "  </nodes>",
                "</jdisc>");

        createModel(root, containerElem);
    }

    @Test
    public void manually_setting_up_search_handler_is_forbidden() {
        try {
            Element clusterElem = DomBuilderTest.parse(
                    "<jdisc id='default' version='1.0'>",
                    "  <handler id='com.yahoo.search.handler.SearchHandler' />",
                    nodesXml,
                    " </jdisc>");


            createModel(root, clusterElem);
            fail("Expected exception");
        } catch (Exception e) {
            assertThat(e.getMessage(), containsString("Setting up com.yahoo.search.handler.SearchHandler manually is not supported"));
        }
    }

    @Test
    public void cluster_is_connected_to_content_clusters() throws Exception {
        String hosts = hostsXml();

        String services = "" +
                "<services>"+
                "  <admin version='2.0'>" +
                "    <adminserver hostalias='mockhost'/>" +
                "  </admin>" +
                "  <jdisc version='1.0' id='container'>"+
                "      <search>" +
                "        <chain id='mychain' inherits='vespa'/>" +
                "      </search>" +
                "      <nodes>"+
                "        <node hostalias=\"mockhost\" />"+
                "      </nodes>"+
                "  </jdisc>"+
                contentXml() +
                "</services>";

        VespaModel model = getVespaModelWithMusic(hosts, services);

        ContainerCluster cluster = model.getContainerClusters().get("container");
        assertFalse(cluster.getSearchChains().localProviders().isEmpty());
    }

    @Test
    public void cluster_is_connected_to_search_clusters() throws Exception {
        String hosts = hostsXml();

        String services = "" +
                "<services>"+
                "  <admin version='2.0'>" +
                "    <adminserver hostalias='mockhost'/>" +
                "  </admin>" +
                "  <jdisc version='1.0' id='container'>"+
                "      <search>" +
                "        <chain id='mychain' inherits='vespa'/>" +
                "      </search>" +
                "      <nodes>"+
                "        <node hostalias=\"mockhost\" />"+
                "      </nodes>"+
                "  </jdisc>"+
                contentXml() +
                "</services>";

        VespaModel model = getVespaModelWithMusic(hosts, services);

        ContainerCluster cluster = model.getContainerClusters().get("container");
        assertFalse(cluster.getSearchChains().localProviders().isEmpty());
    }


    private VespaModel getVespaModelWithMusic(String hosts, String services) {
        return new VespaModelCreatorWithMockPkg(hosts, services, ApplicationPackageUtils.generateSearchDefinitions("music")).create();
    }

    private String hostsXml() {
        return "" +
                    "<hosts>  " +
                    "  <host name=\"node0\">" +
                    "    <alias>mockhost</alias>" +
                    "  </host>" +
                    "</hosts>";
    }

    private String contentXml() {
        return  "  <content version=\"1.0\" id='content'>"+
                "    <documents>\n" +
                "      <document type=\"music\" mode='index'/>\n" +
                "    </documents>\n" +
                "    <redundancy>3</redundancy>"+
                "    <group>"+
                "      <node hostalias=\"mockhost\" distribution-key=\"0\"/>"+
                "    </group>"+
                "  </content>";
    }

}
