// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change;

import com.yahoo.cloud.config.log.LogdConfig;
import com.yahoo.component.Version;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.provision.InMemoryProvisioner;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.container.ComponentsConfig;
import com.yahoo.container.QrConfig;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.application.validation.ValidationTester;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class RestartChangesDefersConfigChangesTest {

    @Test
    public void changes_requiring_restart_defers_config_changes() {
        Version version1 = new Version(1, 2, 3);
        Version version2 = new Version(1, 3, 2);
        Version version3 = new Version(2, 1, 3);
        ValidationTester tester = new ValidationTester(new InMemoryProvisioner(5,
                                                                               new NodeResources(1, 3, 9, 1),
                                                                               true));
        DeployState.Builder state = new DeployState.Builder().vespaVersion(version1).wantedNodeVespaVersion(version2);
        VespaModel gen1 = tester.deploy(null, getServices(5, 3), Environment.prod, null, state).getFirst();

        // Change node count - no restart
        VespaModel gen2 = tester.deploy(gen1, getServices(4, 3), Environment.prod, null, state).getFirst();
        var config2 = new ComponentsConfig.Builder();
        gen2.getContainerClusters().get("default").getContainers().get(0).getConfig(config2);
        assertFalse(config2.getApplyOnRestart());

        // Change memory amount - requires restart
        VespaModel gen3 = tester.deploy(gen2, getServices(4, 2), Environment.prod, null, state).getFirst();
        var config3 = new ComponentsConfig.Builder();
        gen3.getContainerClusters().get("default").getContainers().get(0).getConfig(config3);
        assertTrue(config3.getApplyOnRestart());

        // Change major version - requires restart
        state.vespaVersion(version3);
        VespaModel gen4 = tester.deploy(gen3, getServices(4, 2), Environment.prod, null, state).getFirst();
        var config4 = new ComponentsConfig.Builder();
        gen4.getContainerClusters().get("default").getContainers().get(0).getConfig(config4);
        assertTrue(config4.getApplyOnRestart());
    }

    private static String getServices(int nodes, int memory) {
        return "<services version='1.0'>" +
               "  <container id='default' version='1.0'>" +
               "    <nodes count='" + nodes + "'><resources vcpu='1' memory='" + memory + "Gb' disk='9Gb'/></nodes>" +
               "   </container>" +
               "</services>";
    }

}
