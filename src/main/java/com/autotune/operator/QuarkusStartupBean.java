package com.autotune.operator;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.autotune.utils.KruizeConstants;

@ApplicationScoped
public class QuarkusStartupBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusStartupBean.class);

    @Inject
    KruizeConfig config;

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Initializing Kruize configuration via QuarkusStartupBean...");
        try {
            // Populate KruizeDeploymentInfo fields from typesafe config
            KruizeDeploymentInfo.database_username = config.databaseUsername();
            KruizeDeploymentInfo.database_password = config.databasePassword();
            KruizeDeploymentInfo.database_hostname = config.databaseHostname();
            KruizeDeploymentInfo.database_dbname = config.databaseDbname();
            KruizeDeploymentInfo.database_port = config.databasePort();
            KruizeDeploymentInfo.database_ssl_mode = config.databaseSslmode();
            
            KruizeDeploymentInfo.settings_db_driver = config.settingsDbDriver();
            KruizeDeploymentInfo.settings_hibernate_dialect = config.settingsHibernateDialect();
            KruizeDeploymentInfo.settings_hibernate_connection_driver_class = config.settingsHibernateConnectionDriverClass();
            KruizeDeploymentInfo.settings_hibernate_hbm2ddl_auto = config.settingsHibernateHbm2ddlAuto();
            KruizeDeploymentInfo.settings_hibernate_show_sql = config.settingsHibernateShowSql();
            KruizeDeploymentInfo.settings_hibernate_time_zone = config.settingsHibernateTimeZone();
            KruizeDeploymentInfo.settings_save_to_db = config.settingsSaveToDb();
            
            KruizeDeploymentInfo.autotune_mode = config.autotuneMode();
            KruizeDeploymentInfo.monitoring_agent = config.monitoringAgent();
            KruizeDeploymentInfo.monitoring_service = config.monitoringService();
            KruizeDeploymentInfo.plots = config.plots();
            KruizeDeploymentInfo.log_recommendation_metrics_level = config.logRecommendationMetricsLevel();
            KruizeDeploymentInfo.local = config.local();
            KruizeDeploymentInfo.is_ros_enabled = config.isRosEnabled();
            
            KruizeDeploymentInfo.bulk_update_results_limit = config.bulkUpdateResultsLimit();
            KruizeDeploymentInfo.bulk_api_limit = config.bulkApiLimit();
            KruizeDeploymentInfo.bulk_thread_pool_size = config.bulkThreadPoolSize();
            KruizeDeploymentInfo.generate_recommendations_date_range_limit_in_days = config.generateRecommendationsDateRangeLimitInDays();
            KruizeDeploymentInfo.delete_partition_threshold_in_days = config.deletePartitionThresholdInDays();
            KruizeDeploymentInfo.experiment_name_format = config.experimentNameFormat();
            KruizeDeploymentInfo.namespace_experiment_name_format = config.namespaceExperimentNameFormat();
            
            KruizeDeploymentInfo.is_kafka_enabled = config.isKafkaEnabled();
            KruizeDeploymentInfo.kafka_bootstrap_servers = config.kafkaBootstrapServers().orElse(null);
            KruizeDeploymentInfo.bulk_input_topic = config.bulkInputTopic().orElse(null);
            KruizeDeploymentInfo.kafka_group_id = config.kafkaConsumerGroupId().orElse(null);
            KruizeDeploymentInfo.kafka_topics = config.kafkaTopics().orElse(null);
            KruizeDeploymentInfo.kafka_response_filter_include = config.kafkaResponseFilterInclude().orElse(null);
            KruizeDeploymentInfo.kafka_response_filter_exclude = config.kafkaResponseFilterExclude().orElse(null);
            KruizeDeploymentInfo.kafka_thread_pool_size = config.kafkaThreadPoolSize();
            
            KruizeDeploymentInfo.cloudwatch_logs_access_key_id = config.cloudwatchLogsAccessKeyId().orElse(null);
            KruizeDeploymentInfo.cloudwatch_logs_secret_access_key = config.cloudwatchLogsSecretAccessKey().orElse(null);
            KruizeDeploymentInfo.cloudwatch_logs_log_group = config.cloudwatchLogsLogGroup().orElse(null);
            KruizeDeploymentInfo.cloudwatch_logs_region = config.cloudwatchLogsRegion().orElse(null);
            KruizeDeploymentInfo.cloudwatch_logs_log_level = config.cloudwatchLogsLogLevel().orElse(null);
            KruizeDeploymentInfo.cloudwatch_logs_log_stream = config.cloudwatchLogsLogStream().orElse(null);

            config.monitoringAgentEndpoint().ifPresent(KruizeDeploymentInfo::setMonitoring_agent_endpoint);
            config.authToken().ifPresent(token -> KruizeDeploymentInfo.auth_token = token);

            KruizeDeploymentInfo.setCluster_type(config.clusterType());
            KruizeDeploymentInfo.setKubernetesType(config.k8sType());
            KruizeDeploymentInfo.initiateEventLogging();

            // Kafka Sanity Check (Ported from Autotune.java)
            if (KruizeDeploymentInfo.is_kafka_enabled && (KruizeDeploymentInfo.kafka_bootstrap_servers == null || KruizeDeploymentInfo.kafka_bootstrap_servers.isEmpty())) {
                LOGGER.error(KruizeConstants.KAFKA_CONSTANTS.BOOTSTRAP_SERVER_MISSING);
                throw new IllegalStateException(KruizeConstants.KAFKA_CONSTANTS.BOOTSTRAP_SERVER_MISSING);
            }

            KruizeDeploymentInfo.logDeploymentInfo();
            LOGGER.info("Kruize configuration bridging and validation successfully completed via MicroProfile Config.");
        } catch (Throwable e) {
            LOGGER.error("Fatal exception during Kruize startup configuration: " + e.getMessage(), e);
            // Maintain fail-fast System.exit(1) semantics on startup config failures
            System.exit(1);
        }
    }
}
