/*******************************************************************************
 * Copyright (c) 2022 Red Hat, IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.autotune.operator;

import com.autotune.analyzer.metadataProfiles.MetadataProfileCollection;
import com.autotune.analyzer.metadataProfiles.MetadataProfileDeployment;
import com.autotune.analyzer.performanceProfiles.MetricProfileCollection;
import com.autotune.analyzer.performanceProfiles.PerformanceProfilesDeployment;
import com.autotune.analyzer.autoscaler.settings.AutoscalingSettings;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.common.datasource.DataSourceCollection;
import com.autotune.common.parallelengine.executor.KruizeExecutor;
import com.autotune.common.parallelengine.queue.KruizeQueue;
import com.autotune.common.trials.ExperimentTrial;
import com.autotune.experimentManager.core.ExperimentManager;
import com.autotune.experimentManager.data.ExperimentDetailsMap;
import com.autotune.experimentManager.utils.EMConstants.ParallelEngineConfigs;
import com.autotune.experimentManager.workerimpl.IterationManager;
import com.autotune.utils.CloudWatchAppender;
import com.autotune.utils.KruizeConstants;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.autotune.utils.KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.DATASOURCE_CONNECTION_FAILED;

/**
 * CDI startup bean — replaces the Jetty {@code InitiateListener} (ServletContextListener)
 * and the startup logic in {@code Autotune.main()}.
 *
 * ADR 2.2: Replace InitiateListener completely with @ApplicationScoped CDI holder beans
 * and handle startup routines using @Observes StartupEvent.
 *
 * Ordering guarantee (Quarkus):
 *   Flyway runs before Hibernate ORM boots → Hibernate boots before CDI StartupEvent fires.
 *   So by the time onStart() runs, the schema is fully migrated and the SessionFactory is live.
 */
@ApplicationScoped
public class KruizeStartupBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(KruizeStartupBean.class);

    // EM storage — previously stored as untyped ServletContext attributes.
    // ADR 2.2: typed CDI holder field, accessible via CDI injection in EM services.
    private ExperimentDetailsMap<String, ExperimentTrial> emExperimentDetailsMap;
    private KruizeExecutor emExecutor;

    public ExperimentDetailsMap<String, ExperimentTrial> getEmExperimentDetailsMap() {
        return emExperimentDetailsMap;
    }

    public KruizeExecutor getEmExecutor() {
        return emExecutor;
    }

    /**
     * Main startup entry point — fires after Flyway + Hibernate are fully initialised.
     */
    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("KruizeStartupBean — application startup initiated");
        try {
            // Configure AWS CloudWatch logging (no-op when not configured)
            CloudWatchAppender.configureLoggerForCloudWatchLog();

            LOGGER.info("Kruize starting — is_ros_enabled:{}, local:{}",
                    KruizeDeploymentInfo.is_ros_enabled, KruizeDeploymentInfo.local);

            // ── Experiment Manager CDI context (replaces InitiateListener servlet-context attrs) ──
            initExperimentManagerContext();

            // ── Local (LM) mode startup ────────────────────────────────────────────────────────
            if (KruizeDeploymentInfo.local) {
                initLocalMode();
            }

            // ── Normal / EM mode startup ───────────────────────────────────────────────────────
            String autotuneMode = KruizeDeploymentInfo.autotune_mode;
            if (KruizeConstants.StartUpMode.EM_ONLY_MODE.equalsIgnoreCase(autotuneMode)) {
                startEMOnly();
            } else {
                startNormalMode();
            }

            // ── Autoscaling settings (local mode) ─────────────────────────────────────────────
            if (KruizeDeploymentInfo.local) {
                AutoscalingSettings.getInstance().initialiseAutoscalingSettings();
            }

            LOGGER.info("KruizeStartupBean — startup completed successfully");
        } catch (Throwable e) {
            LOGGER.error("Fatal error during Kruize startup: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Initialise the Experiment Manager in-memory store and thread pool executor.
     * Previously stored as untyped attributes on {@code ServletContext} by {@code InitiateListener}.
     */
    private void initExperimentManagerContext() {
        emExperimentDetailsMap = new ExperimentDetailsMap<>();

        emExecutor = new KruizeExecutor(
                ParallelEngineConfigs.EM_CORE_POOL_SIZE,
                ParallelEngineConfigs.EM_MAX_POOL_SIZE,
                ParallelEngineConfigs.EM_CORE_POOL_KEEPALIVETIME_IN_SECS,
                TimeUnit.SECONDS,
                new KruizeQueue<>(ParallelEngineConfigs.EM_QUEUE_SIZE),
                new ThreadPoolExecutor.AbortPolicy(),
                IterationManager.class
        );

        LOGGER.debug("EM context initialised — emExperimentDetailsMap and emExecutor ready");
    }

    /**
     * Local (LM) mode: load datasources, metric profiles, metadata profiles, autoscaler.
     */
    private void initLocalMode() {
        // load datasources already persisted in DB
        DataSourceCollection.getInstance().loadDataSourcesFromDB();

        try {
            // load datasources from the mounted config file
            // throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException
            DataSourceCollection.getInstance().addDataSourcesFromConfigFile(KruizeConstants.CONFIG_FILE);
        } catch (IOException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            LOGGER.error(DATASOURCE_CONNECTION_FAILED + " {}", e.getMessage());
        }

        // load metric profiles from DB
        MetricProfileCollection.getInstance().loadMetricProfilesFromDB();

        if (KruizeDeploymentInfo.is_ros_enabled) {
            // addMetricProfileFromConfigFile() handles its own exceptions internally
            MetricProfileCollection.getInstance().addMetricProfileFromConfigFile();
        }

        // load metadata profiles from DB
        MetadataProfileCollection.getInstance().loadMetadataProfilesFromDB();

        if (KruizeDeploymentInfo.is_ros_enabled) {
            // addMetadataProfileFromConfigFile() handles its own exceptions internally
            MetadataProfileCollection.getInstance().addMetadataProfileFromConfigFile();
        }
    }

    /**
     * Normal mode — starts the Analyzer (loads KruizeObjects, performance/metadata profiles)
     * and then the Experiment Manager.
     */
    private void startNormalMode() {
        try {
            PerformanceProfilesDeployment.getPerformanceProfiles();
            if (KruizeDeploymentInfo.local) {
                MetadataProfileDeployment.getMetadataProfiles();
            }
            KruizeOperator.getKruizeObjects(new KruizeOperator());
        } catch (Exception e) {
            LOGGER.error("Error during normal-mode startup: {}", e.getMessage(), e);
        }
        startEM();
    }

    /**
     * EM-only mode — skips the Analyzer, starts only the Experiment Manager.
     * ADR 2.6: Experiment Manager is toggleable via configuration.
     */
    private void startEMOnly() {
        startEM();
    }

    private void startEM() {
        ExperimentManager.initializeEM();
        LOGGER.info("ExperimentManager initialised");
    }
}
