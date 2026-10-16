package com.autotune.operator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Optional;

@ConfigMapping(prefix = "kruize")
public interface KruizeConfig {

    @WithName("database.username")
    @WithDefault("postgres")
    String databaseUsername();

    @WithName("database.password")
    @WithDefault("postgres")
    String databasePassword();

    @WithName("database.hostname")
    @WithDefault("localhost")
    String databaseHostname();

    @WithName("database.dbname")
    @WithDefault("postgres")
    String databaseDbname();

    @WithName("database.port")
    @WithDefault("5432")
    String databasePort();

    @WithName("database.sslmode")
    @WithDefault("disable")
    String databaseSslmode();

    @WithName("settings.db.driver")
    @WithDefault("org.postgresql.Driver")
    String settingsDbDriver();

    @WithName("settings.hibernate.dialect")
    @WithDefault("org.hibernate.dialect.PostgreSQLDialect")
    String settingsHibernateDialect();

    @WithName("settings.hibernate.connection.driver.class")
    @WithDefault("org.postgresql.Driver")
    String settingsHibernateConnectionDriverClass();

    @WithName("settings.hibernate.hbm2ddl.auto")
    @WithDefault("none")
    String settingsHibernateHbm2ddlAuto();

    @WithName("settings.hibernate.show.sql")
    @WithDefault("false")
    String settingsHibernateShowSql();

    @WithName("settings.hibernate.time.zone")
    @WithDefault("UTC")
    String settingsHibernateTimeZone();

    @WithName("settings.save.to.db")
    @WithDefault("false")
    Boolean settingsSaveToDb();

    @WithName("autotune.mode")
    @WithDefault("normal")
    String autotuneMode();

    @WithName("monitoring.agent")
    @WithDefault("prometheus")
    String monitoringAgent();

    @WithName("monitoring.service")
    @WithDefault("prometheus-k8s")
    String monitoringService();

    @WithName("monitoring.agent.endpoint")
    Optional<String> monitoringAgentEndpoint();

    @WithName("cluster.type")
    @WithDefault("kubernetes")
    String clusterType();

    @WithName("k8s.type")
    @WithDefault("kubernetes")
    String k8sType();

    @WithName("auth.type")
    @WithDefault("none")
    String authType();

    @WithName("auth.token")
    Optional<String> authToken();

    @WithName("plots")
    @WithDefault("true")
    Boolean plots();

    @WithName("log.recommendation.metrics.level")
    @WithDefault("error|critical")
    String logRecommendationMetricsLevel();

    @WithName("local")
    @WithDefault("false")
    Boolean local();

    @WithName("is.ros.enabled")
    @WithDefault("true")
    Boolean isRosEnabled();

    @WithName("bulk.update.results.limit")
    @WithDefault("100")
    Integer bulkUpdateResultsLimit();

    @WithName("bulk.api.limit")
    @WithDefault("1000")
    Integer bulkApiLimit();

    @WithName("bulk.thread.pool.size")
    @WithDefault("3")
    Integer bulkThreadPoolSize();

    @WithName("generate.recommendations.date.range.limit.in.days")
    @WithDefault("15")
    Integer generateRecommendationsDateRangeLimitInDays();

    @WithName("delete.partition.threshold.in.days")
    @WithDefault("15")
    Integer deletePartitionThresholdInDays();

    @WithName("experiment.name.format")
    @WithDefault("%datasource%|%clustername%|%namespace%|%workloadname%(%workloadtype%)|%containername%")
    String experimentNameFormat();

    @WithName("namespace.experiment.name.format")
    @WithDefault("%datasource%|%clustername%|%namespace%")
    String namespaceExperimentNameFormat();

    @WithName("is.kafka.enabled")
    @WithDefault("false")
    Boolean isKafkaEnabled();

    @WithName("kafka.bootstrap.servers")
    Optional<String> kafkaBootstrapServers();

    @WithName("bulk.input.topic")
    Optional<String> bulkInputTopic();

    @WithName("kafka.consumer.group.id")
    Optional<String> kafkaConsumerGroupId();

    @WithName("kafka.topics")
    Optional<String> kafkaTopics();

    @WithName("kafka.response.filter.include")
    Optional<String> kafkaResponseFilterInclude();

    @WithName("kafka.response.filter.exclude")
    Optional<String> kafkaResponseFilterExclude();

    @WithName("kafka.thread.pool.size")
    @WithDefault("3")
    Integer kafkaThreadPoolSize();

    @WithName("cloudwatch.logs.access.key.id")
    Optional<String> cloudwatchLogsAccessKeyId();

    @WithName("cloudwatch.logs.secret.access.key")
    Optional<String> cloudwatchLogsSecretAccessKey();

    @WithName("cloudwatch.logs.log.group")
    Optional<String> cloudwatchLogsLogGroup();

    @WithName("cloudwatch.logs.region")
    Optional<String> cloudwatchLogsRegion();

    @WithName("cloudwatch.logs.log.level")
    Optional<String> cloudwatchLogsLogLevel();

    @WithName("cloudwatch.logs.log.stream")
    Optional<String> cloudwatchLogsLogStream();
}
