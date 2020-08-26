// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation;

import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.vespa.model.VespaModel;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks that the generated model does not have resources that exceeds the given quota.
 *
 * @author ogronnesby
 */
public class QuotaValidator extends Validator {
    @Override
    public void validate(VespaModel model, DeployState deployState) {
        var quota = deployState.getProperties().quota();
        quota.maxClusterSize().ifPresent(maxClusterSize -> validateMaxClusterSize(maxClusterSize, model));
        quota.budget().ifPresent(budget -> validateBudget(budget, model));
    }

    private void validateBudget(int budget, VespaModel model) {
        Optional<Double> spend = model.allClusters().stream()
                .map(clusterId -> model.provisioned().all().get(clusterId))
                .map(Capacity::maxResources)
                .map(clusterCapacity -> clusterCapacity.nodeResources().cost() * clusterCapacity.nodes())
                .reduce(Double::sum);

        if(spend.isPresent() && spend.get() > budget)
            throw new IllegalArgumentException("Hourly spend for maximum specified resources ($"+(int)Math.ceil(spend.get())+") exceeds budget from quota ($"+budget+")!");
    }

    /** Check that all clusters in the application do not exceed the quota max cluster size. */
    private void validateMaxClusterSize(int maxClusterSize, VespaModel model) {
        var invalidClusters = model.allClusters().stream()
                .filter(clusterId -> {
                    var cluster = model.provisioned().all().get(clusterId);
                    var clusterSize = cluster.maxResources().nodes();
                    return clusterSize > maxClusterSize;
                })
                .map(ClusterSpec.Id::value)
                .collect(Collectors.toList());

        if (!invalidClusters.isEmpty()) {
            var clusterNames = String.join(", ", invalidClusters);
            throw new IllegalArgumentException("Clusters " + clusterNames + " exceeded max cluster size of " + maxClusterSize);
        }
    }
}
