/*
 * Copyright (c) 2012-2018 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.runtime.runner;

import java.util.List;
import jakarta.batch.api.listener.JobListener;
import jakarta.batch.runtime.BatchStatus;

import org.jberet._private.BatchLogger;
import org.jberet.creation.JobScopedContextImpl;
import org.jberet.job.model.Job;
import org.jberet.job.model.JobElement;
import org.jberet.job.model.Listeners;
import org.jberet.job.model.RefArtifact;
import org.jberet.runtime.JobExecutionImpl;
import org.jberet.runtime.context.JobContextImpl;
import org.jberet.spi.JobTask;
import org.jboss.logging.Logger;

public final class JobExecutionRunner extends CompositeExecutionRunner<JobContextImpl> implements JobTask {
    private final Job job;

    Logger logger = Logger.getLogger(JobExecutionRunner.class);

    public JobExecutionRunner(final JobContextImpl jobContext) {
        super(jobContext, null);
        this.job = jobContext.getJob();
    }

    @Override
    protected List<? extends JobElement> getJobElements() {
        return job.getJobElements();
    }

    @Override
    public void run() {
        final JobExecutionImpl jobExecution = batchContext.getJobExecution();

        logger.infof("job run: %s", jobExecution.dump());

        // the job may be stopped right after starting
        if (jobExecution.getBatchStatus() != BatchStatus.STOPPING) {
            jobExecution.setBatchStatus(BatchStatus.STARTED);
            batchContext.getJobRepository().updateJobExecution(jobExecution, false, false);

            JobListener[] jobListeners = null;
            int i = 0;

            try {
                jobListeners = createJobListeners();
                for (; i < jobListeners.length; i++) {
                    jobListeners[i].beforeJob();
                }

                runFromHeadOrRestartPoint(jobExecution.getRestartPosition());

                if (jobExecution.isStopRequested()) {
                    jobExecution.setBatchStatus(BatchStatus.STOPPED);
                } else if (jobExecution.getBatchStatus() == BatchStatus.STARTED) {
                    jobExecution.setBatchStatus(BatchStatus.COMPLETED);
                }
            } catch (final Throwable e) {
                BatchLogger.LOGGER.failToRunJob(e, job.getId(), "", job);
                jobExecution.setBatchStatus(BatchStatus.FAILED);
                if (jobListeners == null) {
                    jobExecution.setExitStatus(e.toString());
                }
            } finally {
                if (jobListeners != null && jobListeners.length > 0) {
                    for (i = 0; i < jobListeners.length; i++) {
                        try {
                            jobListeners[i].afterJob();
                        } catch (final Throwable e) {
                            BatchLogger.LOGGER.failToRunJob(e, job.getId(), "", jobListeners[i]);
                            jobExecution.setBatchStatus(BatchStatus.FAILED);
                        }
                    }
                    batchContext.destroyArtifact(jobListeners);
                }
            }
        }

        boolean saveJobParameters = false;
        switch (jobExecution.getBatchStatus()) {
            case COMPLETED:
                break;
            case STARTED:
                jobExecution.setBatchStatus(BatchStatus.COMPLETED);
                break;
            case STOPPING:
                jobExecution.setBatchStatus(BatchStatus.STOPPED);
                //fall through
            case FAILED:
            case STOPPED:
                saveJobParameters = adjustRestartFailedOrStopped(jobExecution);
                break;
        }

        batchContext.getJobRepository().updateJobExecution(jobExecution, true, saveJobParameters);
        batchContext.setTransientUserData(null);

        JobScopedContextImpl.ScopedInstance.destroy(batchContext.getScopedBeans());
        jobExecution.cleanUp();
    }

    /**
     * Adjusts restart position and job xml name if needed for FAILED or STOPPED job execution.
     *
     * @param jobExecution a failed or stopped job execution
     * @return true if the internal job parameter with key {@link org.jberet.job.model.Job#JOB_XML_NAME} was added to
     * {@code jobExecution}; false otherwise.
     */
    private boolean adjustRestartFailedOrStopped(final JobExecutionImpl jobExecution) {
        if (!job.getRestartableBoolean()) {
            jobExecution.setRestartPosition(Job.UNRESTARTABLE);
        }
        if (job.getJobXmlName() != null) {
            //jobXmlName is different than jobId, save it so the restart can correctly locate job xml file if the job
            //is not available from the cache.
            jobExecution.addJobParameter(Job.JOB_XML_NAME, job.getJobXmlName());
            return true;
        }
        return false;
    }

    private JobListener[] createJobListeners() {
        final Listeners listeners = job.getListeners();
        if (listeners != null) {
            final List<RefArtifact> listenerList = listeners.getListeners();
            final int count = listenerList.size();
            final JobListener[] jobListeners = new JobListener[count];
            for (int i = 0; i < count; i++) {
                final RefArtifact listener = listenerList.get(i);
                jobListeners[i] = batchContext.createArtifact(listener.getRef(), null, listener.getProperties());
            }
            return jobListeners;
        } else {
            return new JobListener[0];
        }
    }

    @Override
    public int getRequiredRemainingPermits() {
        return 2;
    }
}
