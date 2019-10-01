// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration;

import com.yahoo.config.provision.ApplicationId;

import java.util.Objects;

/**
 * @author jonmv
 */
// TODO jonmv: Remove this.
public interface BuildService {

    /**
     * Enqueues a job defined by buildJob in an external build system.
     *
     * Implementations should throw an exception if the triggering fails.
     */
    void trigger(BuildJob buildJob);

    /**
     * Returns the state of the given job in the build service.
     */
    JobState stateOf(BuildJob buildJob);

    enum JobState {

        /** Job is not running, and may be triggered. */
        idle,

        /** Job is already running, and will be queued if triggered now. */
        running,

        /** Job is running and queued and will automatically be started again after it finishes its current run. */
        queued,

        /** Job is disabled, i.e., it can not be triggered. */
        disabled

    }


    class BuildJob {

        private final ApplicationId applicationId;
        private final long projectId;
        private final String jobName;

        protected BuildJob(ApplicationId applicationId, long projectId, String jobName) {
            this.applicationId = applicationId;
            this.projectId = projectId;
            this.jobName = jobName;
        }

        public static BuildJob of(ApplicationId applicationId, long projectId, String jobName) {
            return new BuildJob(applicationId, projectId, jobName);
        }

        public ApplicationId applicationId() { return applicationId; }
        public long projectId() { return projectId; }
        public String jobName() { return jobName; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BuildJob)) return false;
            BuildJob job = (BuildJob) o;
            return Objects.equals(applicationId, job.applicationId) &&
                   Objects.equals(jobName, job.jobName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(applicationId, jobName);
        }

        @Override
        public String toString() {
            return jobName + " for " + applicationId + " with project " + projectId;
        }

    }

}
