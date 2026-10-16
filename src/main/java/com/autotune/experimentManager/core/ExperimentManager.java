package com.autotune.experimentManager.core;

import com.autotune.experimentManager.settings.EMS;
import com.autotune.experimentManager.utils.EMConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experiment Manager initialisation helper.
 * Servlet registration removed — EM endpoints are wired via JAX-RS {@code @Path}.
 * Called from {@link com.autotune.operator.KruizeStartupBean} on {@code StartupEvent}.
 */
public class ExperimentManager {
    public static EMExecutorService emExecutorService;
    public static EMStageProcessor emStageProcessor;
    public static EMScheduledStageProcessor emScheduledStageProcessor;

    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentManager.class);

    public static void initializeEM() {
        emExecutorService = EMExecutorService.getService();
        emStageProcessor = new EMStageProcessor();
        emScheduledStageProcessor = new EMScheduledStageProcessor();
        LOGGER.info(EMConstants.Logs.ExperimentManager.INITIALIZE_EM);

        if (!performEnvCheck()) {
            LOGGER.warn("EM environment check failed — continuing anyway");
        }
        if (!performConfigCheck()) {
            LOGGER.warn("EM config check failed — continuing anyway");
        }

        emExecutorService.createExecutors(EMS.getController().getCurrentExecutors());
        emExecutorService.initiateExperimentStageProcessor(emStageProcessor);
        emExecutorService.initiateExperimentStageProcessor(emScheduledStageProcessor);
        LOGGER.info("EM version: testv1 — initialised");
    }

    public static boolean performEnvCheck() {
        // TODO: Read the system limits and detect the environment
        return true;
    }

    public static boolean performConfigCheck() {
        // TODO: Read the config and change settings accordingly
        return true;
    }

    public static void notifyQueueProcessor() {
        emStageProcessor.notifyProcessor();
    }

    public static void notifyScheduledQueueProcessor() {
        emScheduledStageProcessor.notifyProcessor();
    }
}
