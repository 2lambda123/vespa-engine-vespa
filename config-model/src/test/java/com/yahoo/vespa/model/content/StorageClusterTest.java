// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content;

import com.yahoo.vespa.config.content.core.StorIntegritycheckerConfig;
import com.yahoo.vespa.config.content.core.StorVisitorConfig;
import com.yahoo.vespa.config.content.StorFilestorConfig;
import com.yahoo.vespa.config.content.core.StorServerConfig;
import com.yahoo.vespa.config.content.PersistenceConfig;
import com.yahoo.vespa.config.storage.StorDevicesConfig;
import com.yahoo.config.model.test.MockRoot;
import com.yahoo.documentmodel.NewDocumentType;
import com.yahoo.text.XML;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.model.content.cluster.ContentCluster;
import com.yahoo.vespa.model.content.storagecluster.StorageCluster;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Collections;

import static org.junit.Assert.*;

public class StorageClusterTest {

    StorageCluster parse(String xml) {
        MockRoot root = new MockRoot();
        root.getDeployState().getDocumentModel().getDocumentManager().add(
                new NewDocumentType(new NewDocumentType.Name("music"))
        );
        root.getDeployState().getDocumentModel().getDocumentManager().add(
                new NewDocumentType(new NewDocumentType.Name("movies"))
        );
        Document doc = XML.getDocument(xml);
        Element clusterElem = doc.getDocumentElement();
        ContentCluster cluster = new ContentCluster.Builder(null, null).build(Collections.emptyList(), root, clusterElem);

        root.freezeModelTopology();
        return cluster.getStorageNodes();
    }

    @Test
    public void testBasics() {
        StorServerConfig.Builder builder = new StorServerConfig.Builder();
        parse("<content id=\"foofighters\"><documents/>\n" +
              "  <group>" +
              "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
              "  </group>" +
              "</content>\n").
              getConfig(builder);

        StorServerConfig config = new StorServerConfig(builder);
        assertEquals(false, config.is_distributor());
        assertEquals("foofighters", config.cluster_name());
    }

    @Test
    public void testMerges() {
        StorServerConfig.Builder builder = new StorServerConfig.Builder();
        parse("" +
                "<content id=\"foofighters\">\n" +
                "  <documents/>" +
                "  <tuning>" +
                "    <merges max-per-node=\"1K\" max-queue-size=\"10K\"/>\n" +
                "  </tuning>" +
                "  <group>" +
                "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
                "  </group>" +
                "</content>"
        ).getConfig(builder);

        StorServerConfig config = new StorServerConfig(builder);
        assertEquals(1024, config.max_merges_per_node());
        assertEquals(1024*10, config.max_merge_queue_size());
    }

    @Test
    public void testVisitors() {
        StorVisitorConfig.Builder builder = new StorVisitorConfig.Builder();
        parse(
                "<cluster id=\"bees\">\n" +
                "  <documents/>" +
                "  <tuning>\n" +
                "    <visitors thread-count=\"7\" max-queue-size=\"1000\">\n" +
                "      <max-concurrent fixed=\"42\" variable=\"100\"/>\n" +
                "    </visitors>\n" +
                "  </tuning>\n" +
                "  <group>" +
                "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
                "  </group>" +
                "</cluster>"
        ).getConfig(builder);

        StorVisitorConfig config = new StorVisitorConfig(builder);
        assertEquals(42, config.maxconcurrentvisitors_fixed());
        assertEquals(100, config.maxconcurrentvisitors_variable());
        assertEquals(7, config.visitorthreads());
        assertEquals(1000, config.maxvisitorqueuesize());
    }

    @Test
    public void testPersistenceThreads() {
        StorFilestorConfig.Builder builder = new StorFilestorConfig.Builder();
        parse(
                "<cluster id=\"bees\">\n" +
                "    <documents/>" +
                "    <engine>" +
                 "     <vds/>" +
                "    </engine>" +
                "    <tuning>\n" +
                "        <persistence-threads>\n" +
                "            <thread lowest-priority=\"VERY_LOW\" count=\"2\"/>\n" +
                "            <thread lowest-priority=\"VERY_HIGH\" count=\"1\"/>\n" +
                "            <thread count=\"1\"/>\n" +
                "        </persistence-threads>\n" +
                "    </tuning>\n" +
                "  <group>" +
                "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
                "  </group>" +
                "</cluster>"
        ).getConfig(builder);

        StorFilestorConfig config = new StorFilestorConfig(builder);

        assertEquals(4, config.threads().size());
        assertEquals(190, config.threads().get(0).lowestpri());
        assertEquals(190, config.threads().get(1).lowestpri());
        assertEquals(60, config.threads().get(2).lowestpri());
        assertEquals(255, config.threads().get(3).lowestpri());

        assertEquals(true, config.enable_multibit_split_optimalization());
    }

    @Test
    public void testNoPersistenceThreads() {
        StorFilestorConfig.Builder builder = new StorFilestorConfig.Builder();
        parse(
                "<cluster id=\"bees\">\n" +
                        "    <documents/>" +
                        "    <tuning>\n" +
                        "    </tuning>\n" +
                        "  <group>" +
                        "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
                        "  </group>" +
                        "</cluster>"
        ).getConfig(builder);

        StorFilestorConfig config = new StorFilestorConfig(builder);

        assertEquals(0, config.threads().size());
    }

    @Test
    public void testMaintenance() {
        StorIntegritycheckerConfig.Builder builder = new StorIntegritycheckerConfig.Builder();
        parse(
                "<cluster id=\"bees\">\n" +
                "  <documents/>" +
                "  <tuning>" +
                "    <maintenance start=\"01:00\" stop=\"02:00\" high=\"tuesday\"/>\n" +
                "  </tuning>" +
                "  <group>" +
                "     <node distribution-key=\"0\" hostalias=\"mockhost\"/>" +
                "  </group>" +
                "</cluster>"
             ).getConfig(builder);
        StorIntegritycheckerConfig config = new StorIntegritycheckerConfig(builder);

        assertEquals(60, config.dailycyclestart());
        assertEquals(120, config.dailycyclestop());
        assertEquals("rrRrrrr", config.weeklycycle());
    }

    @Test
    public void testCapacity() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                        "  <documents/>" +
                        "  <group>\n" +
                        "    <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                        "    <node distribution-key=\"1\" hostalias=\"mockhost\" capacity=\"1.5\"/>\n" +
                        "    <node distribution-key=\"2\" hostalias=\"mockhost\" capacity=\"2.0\"/>\n" +
                        "  </group>\n" +
                        "</cluster>"
        );

        ContentCluster cluster = new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());

        for (int i = 0; i < 3; ++i) {
            StorageNode node = cluster.getStorageNodes().getChildren().get("" + i);
            StorServerConfig.Builder builder = new StorServerConfig.Builder();
            cluster.getStorageNodes().getConfig(builder);
            node.getConfig(builder);
            StorServerConfig config = new StorServerConfig(builder);
            assertEquals(1.0 + (double)i * 0.5, config.node_capacity(), 0.001);
        }
    }

    @Test
    public void testRootFolder() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                        "  <documents/>" +
                        "  <group>\n" +
                        "    <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                        "  </group>\n" +
                        "</cluster>"
        );

        ContentCluster cluster = new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());

        StorageNode node = cluster.getStorageNodes().getChildren().get("0");

        {
            StorDevicesConfig.Builder builder = new StorDevicesConfig.Builder();
            node.getConfig(builder);
            StorDevicesConfig config = new StorDevicesConfig(builder);
            assertEquals(Defaults.getDefaults().vespaHome() + "var/db/vespa/vds/storage/storage/0", config.root_folder());
        }

        {
            StorServerConfig.Builder builder = new StorServerConfig.Builder();
            cluster.getStorageNodes().getConfig(builder);
            node.getConfig(builder);
            StorServerConfig config = new StorServerConfig(builder);
            assertEquals(Defaults.getDefaults().vespaHome() + "var/db/vespa/vds/storage/storage/0", config.root_folder());
        }

        {
            StorServerConfig.Builder builder = new StorServerConfig.Builder();
            cluster.getDistributorNodes().getConfig(builder);
            cluster.getDistributorNodes().getChildren().get("0").getConfig(builder);
            StorServerConfig config = new StorServerConfig(builder);
            assertEquals(Defaults.getDefaults().vespaHome() + "var/db/vespa/vds/storage/distributor/0", config.root_folder());
        }
    }

    @Test
    public void testGenericPersistenceTuning() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                        "<documents/>" +
                        "<engine>\n" +
                        "    <fail-partition-on-error>true</fail-partition-on-error>\n" +
                        "    <revert-time>34m</revert-time>\n" +
                        "    <recovery-time>5d</recovery-time>\n" +
                        "</engine>" +
                        "  <group>\n" +
                        "    <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                        "  </group>\n" +
                        "</cluster>"
        );

        ContentCluster cluster = new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());

        PersistenceConfig.Builder builder = new PersistenceConfig.Builder();
        cluster.getStorageNodes().getConfig(builder);

        PersistenceConfig config = new PersistenceConfig(builder);
        assertEquals(true, config.fail_partition_on_error());
        assertEquals(34 * 60, config.revert_time_period());
        assertEquals(5 * 24 * 60 * 60, config.keep_remove_time_period());
    }

    @Test
    public void requireThatUserDoesntSpecifyBothGroupAndNodes() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                        "<engine>\n" +
                        "    <fail-partition-on-error>true</fail-partition-on-error>\n" +
                        "    <revert-time>34m</revert-time>\n" +
                        "    <recovery-time>5d</recovery-time>\n" +
                        "</engine>" +
                        "  <group>\n" +
                        "    <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                        "  </group>\n" +
                        "  <nodes>\n" +
                        "    <node distribution-key=\"1\" hostalias=\"mockhost\"/>\n" +
                        "  </nodes>\n" +
                        "</cluster>"
        );

        try {
            new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());
            assertTrue(false);
        } catch (Exception e) {

        }
    }

    @Test
    public void requireThatGroupNamesMustBeUniqueAmongstSiblings() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                "<documents/>\n" +
                "  <group>\n" +
                "    <distribution partitions=\"*\"/>\n" +
                "    <group distribution-key=\"0\" name=\"bar\">\n" +
                "      <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                "    </group>\n" +
                "    <group distribution-key=\"0\" name=\"bar\">\n" +
                "      <node distribution-key=\"1\" hostalias=\"mockhost\"/>\n" +
                "    </group>\n" +
                "  </group>\n" +
                "</cluster>"
        );
        try {
            new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());
            fail("Did not get exception with duplicate group names");
        } catch (RuntimeException e) {
            assertEquals("Cluster 'storage' has multiple groups with name 'bar' in the same subgroup. " +
                         "Group sibling names must be unique.", e.getMessage());
        }
    }

    @Test
    public void requireThatGroupNamesCanBeDuplicatedAcrossLevels() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                "<documents/>\n" +
                "  <group>\n" +
                "    <distribution partitions=\"*\"/>\n" +
                "    <group distribution-key=\"0\" name=\"bar\">\n" +
                "      <group distribution-key=\"0\" name=\"foo\">\n" +
                "        <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                "      </group>\n" +
                "    </group>\n" +
                "    <group distribution-key=\"0\" name=\"foo\">\n" +
                "      <group distribution-key=\"0\" name=\"bar\">\n" +
                "        <node distribution-key=\"1\" hostalias=\"mockhost\"/>\n" +
                "      </group>\n" +
                "    </group>\n" +
                "  </group>\n" +
                "</cluster>"
        );
        // Should not throw.
        new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());
    }

    @Test
    public void requireThatNestedGroupsRequireDistribution() {
        Document doc = XML.getDocument(
                "<cluster id=\"storage\">\n" +
                        "<documents/>\n" +
                        "  <group>\n" +
                        "    <group distribution-key=\"0\" name=\"bar\">\n" +
                        "      <node distribution-key=\"0\" hostalias=\"mockhost\"/>\n" +
                        "    </group>\n" +
                        "    <group distribution-key=\"0\" name=\"baz\">\n" +
                        "      <node distribution-key=\"1\" hostalias=\"mockhost\"/>\n" +
                        "    </group>\n" +
                        "  </group>\n" +
                        "</cluster>"
        );
        try {
            new ContentCluster.Builder(null, null).build(Collections.emptyList(), new MockRoot(), doc.getDocumentElement());
            fail("Did not get exception with missing distribution element");
        } catch (RuntimeException e) {
            assertEquals("'distribution' attribute is required with multiple subgroups", e.getMessage());
        }
    }
}
