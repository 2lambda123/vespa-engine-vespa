// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.servicedump;

import com.yahoo.vespa.hosted.node.admin.task.util.process.CommandResult;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Produces service dump artifacts.
 *
 * @author bjorncs
 */
interface ArtifactProducer {

    String artifactName();
    String description();
    List<Artifact> produceArtifacts(Context ctx);

    interface Context {
        String serviceId();
        int servicePid();
        CommandResult executeCommandInNode(List<String> command, boolean logOutput);
        Path outputDirectoryInNode();
        Path pathInNodeUnderVespaHome(String relativePath);
        Path pathOnHostFromPathInNode(Path pathInNode);
        Options options();

        interface Options {
            OptionalDouble duration();
            boolean callGraphRecording();
            boolean sendProfilingSignal();
        }
    }


}
