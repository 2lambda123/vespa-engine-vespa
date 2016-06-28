// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content.utils;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.model.api.HostProvisioner;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.provision.InMemoryProvisioner;
import com.yahoo.config.model.provision.SingleNodeProvisioner;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.config.model.test.MockRoot;
import com.yahoo.text.XML;
import com.yahoo.vespa.model.content.cluster.ContentCluster;
import com.yahoo.vespa.model.test.utils.ApplicationPackageUtils;
import org.w3c.dom.Document;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * For testing purposes only.
 * @author geirst
 */
public class ContentClusterUtils {

    public static MockRoot createMockRoot(String[] hosts) throws Exception {
        return createMockRoot(hosts, ApplicationPackageUtils.generateSearchDefinition("test"));
    }

    private static MockRoot createMockRoot(HostProvisioner provisioner, List<String> searchDefinitions) {
        ApplicationPackage applicationPackage = new MockApplicationPackage.Builder().withSearchDefinitions(searchDefinitions).build();
        DeployState deployState = new DeployState.Builder()
                .applicationPackage(applicationPackage)
                .modelHostProvisioner(provisioner)
                .build();
        return new MockRoot("", deployState);

    }
    public static MockRoot createMockRoot(String[] hosts, List<String> searchDefinitions) throws Exception {
        return createMockRoot(new InMemoryProvisioner(true, hosts), searchDefinitions);
    }

    public static MockRoot createMockRoot(List<String> searchDefinitions) {
        return createMockRoot(new SingleNodeProvisioner(), searchDefinitions);
    }

    public static ContentCluster createCluster(String clusterXml, MockRoot root) throws Exception {
        Document doc = XML.getDocument(clusterXml);
        return new ContentCluster.Builder(null, null).build(Collections.emptyList(), root, doc.getDocumentElement());
    }

    public static ContentCluster createCluster(String clusterXml, List<String> searchDefinitions) throws Exception {
        MockRoot root = createMockRoot(searchDefinitions);
        ContentCluster cluster = createCluster(clusterXml, root);
        root.freezeModelTopology();
        cluster.validate();
        return cluster;
    }

    public static ContentCluster createCluster(String clusterXml) throws Exception {
        return createCluster(clusterXml, ApplicationPackageUtils.generateSearchDefinitions("test"));
    }

    public static String createClusterXml(String groupXml, int redundancy, int searchableCopies) {
        return createClusterXml(groupXml, Optional.empty(), redundancy, searchableCopies);
    }

    public static String createClusterXml(String groupXml, Optional<String> dispatchXml, int redundancy, int searchableCopies) {
        return new ContentClusterBuilder().
                groupXml(groupXml).
                dispatchXml(dispatchXml).
                redundancy(redundancy).
                searchableCopies(searchableCopies).getXml();
    }

}
