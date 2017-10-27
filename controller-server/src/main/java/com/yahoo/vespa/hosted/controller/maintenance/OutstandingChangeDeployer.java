// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.application.ApplicationList;
import com.yahoo.vespa.hosted.controller.application.Change;

import java.time.Duration;

/**
 * Deploys application changes which have been postponed due to an ongoing upgrade
 * 
 * @author bratseth
 */
public class OutstandingChangeDeployer extends Maintainer {
    
    public OutstandingChangeDeployer(Controller controller, Duration interval, JobControl jobControl) {
        super(controller, interval, jobControl);
    }

    @Override
    protected void maintain() {
        ApplicationList applications = controller().applications().list().notPullRequest();
        for (ApplicationList.Entry entry : applications.asList()) {
            if (entry.hasOutstandingChange() &&  ! entry.deploying().isPresent())
                controller().applications().deploymentTrigger().triggerChange(entry.id(),
                                                                              Change.ApplicationChange.unknown());
        }
    }

}
