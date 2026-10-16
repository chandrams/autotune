/*******************************************************************************
 * Copyright (c) 2026 Red Hat, IBM Corporation and others.
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

package com.autotune.analyzer.services;

import com.autotune.analyzer.adapters.DeviceDetailsAdapter;
import com.autotune.analyzer.adapters.MetricAggregationInfoResultsIntSerializer;
import com.autotune.analyzer.adapters.MetricMetadataAdapter;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.FetchMetricsError;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.recommendations.Config;
import com.autotune.analyzer.recommendations.ContainerRecommendations;
import com.autotune.analyzer.recommendations.NamespaceRecommendations;
import com.autotune.analyzer.recommendations.RecommendationConfigItem;
import com.autotune.analyzer.recommendations.engine.RecommendationEngine;
import com.autotune.analyzer.recommendations.objects.MappedRecommendationForModel;
import com.autotune.analyzer.recommendations.objects.MappedRecommendationForTimestamp;
import com.autotune.analyzer.recommendations.objects.TermRecommendations;
import com.autotune.analyzer.serviceObjects.ContainerAPIObject;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.serviceObjects.KubernetesAPIObject;
import com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.analyzer.utils.ServiceHelpers;
import com.autotune.common.data.metrics.MetricAggregationInfoResults;
import com.autotune.common.data.metrics.MetricMetadata;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.operator.KruizeDeploymentInfo;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.MetricsConfig;
import com.autotune.utils.Utils;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.autotune.analyzer.utils.AnalyzerConstants.LOCAL;
import static com.autotune.analyzer.utils.AnalyzerConstants.REMOTE;
import static com.autotune.analyzer.utils.AnalyzerConstants.VersionConstants.APIVersionConstants.CURRENT_RECOMMENDATIONS_API_VERSION;
import static com.autotune.utils.KruizeConstants.KRUIZE_BULK_API.JOB_ID;

/**
 * REST API v1 endpoint for getting recommendations with the new schema.
 * Endpoint: /kruize/api/v1/recommendations
 */
@Path("/kruize/api/v1/recommendations")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class RecommendationsResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationsResource.class);
    private final AtomicInteger counter = new AtomicInteger(0);

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(AnalyzerConstants.ServiceConstants.EXPERIMENT_NAME) String experimentName,
            @QueryParam(AnalyzerConstants.ServiceConstants.LATEST) String latestRecommendation,
            @QueryParam(KruizeConstants.JSONKeys.MONITORING_END_TIME) String monitoringEndTime,
            @QueryParam(AnalyzerConstants.ServiceConstants.RM) String rm,
            @QueryParam(JOB_ID) String bulkJobID) {
        LOGGER.info("RecommendationsResource GET request received");
        String statusValue = KruizeConstants.APIMessages.FAILURE;
        Timer.Sample timer = Timer.start(MetricsConfig.meterRegistry());

        Timestamp monitoringEndTimestamp = null;
        Map<String, KruizeObject> mKruizeExperimentMap = new ConcurrentHashMap<>();
        boolean getLatest = true;
        boolean checkForTimestamp = false;
        boolean error = false;
        boolean rmTable = false;

        if (latestRecommendation != null && !latestRecommendation.isEmpty()
                && latestRecommendation.equalsIgnoreCase(AnalyzerConstants.BooleanString.FALSE)) {
            getLatest = false;
        }
        if (rm != null && !rm.isEmpty() && rm.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) {
            rmTable = true;
        }

        List<KruizeObject> kruizeObjectList = new ArrayList<>();
        try {
            if (experimentName != null) {
                experimentName = experimentName.trim();
                try {
                    if (rmTable)
                        new ExperimentDBService().loadExperimentAndRecommendationsFromDBByName(mKruizeExperimentMap, experimentName);
                    else
                        new ExperimentDBService().loadLMExperimentAndRecommendationsFromDBByName(mKruizeExperimentMap, experimentName, bulkJobID);
                } catch (Exception e) {
                    LOGGER.error("Loading saved experiment {} failed: {} ", experimentName, e.getMessage());
                }
                if (mKruizeExperimentMap.containsKey(experimentName)) {
                    if (monitoringEndTime != null && !monitoringEndTime.isEmpty()) {
                        monitoringEndTime = monitoringEndTime.trim();
                        if (Utils.DateUtils.isAValidDate(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime)) {
                            Date mEndTime = Utils.DateUtils.getDateFrom(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime);
                            monitoringEndTimestamp = new Timestamp(mEndTime.getTime());
                            boolean timestampExists = ServiceHelpers.KruizeObjectOperations
                                    .checkRecommendationTimestampExists(mKruizeExperimentMap.get(experimentName), monitoringEndTime);
                            if (timestampExists) {
                                checkForTimestamp = true;
                            } else {
                                error = true;
                                return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new Gson().toJson(new KruizeResponse(
                                                String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.RECOMMENDATION_DOES_NOT_EXIST_MSG, monitoringEndTime),
                                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                                        .build();
                            }
                        } else {
                            error = true;
                            return Response.status(Response.Status.BAD_REQUEST)
                                    .entity(new Gson().toJson(new KruizeResponse(
                                            String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_TIMESTAMP_MSG, monitoringEndTime),
                                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                                    .build();
                        }
                    }
                    kruizeObjectList.add(mKruizeExperimentMap.get(experimentName));
                } else {
                    error = true;
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_EXPERIMENT_NAME_MSG, experimentName),
                                    Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                try {
                    if (rmTable)
                        new ExperimentDBService().loadAllExperimentsAndRecommendations(mKruizeExperimentMap);
                    else
                        new ExperimentDBService().loadAllLMExperimentsAndRecommendations(mKruizeExperimentMap, bulkJobID);
                } catch (Exception e) {
                    LOGGER.error("Loading saved experiments failed: {} ", e.getMessage());
                }
                if (monitoringEndTime != null && !monitoringEndTime.isEmpty()) {
                    monitoringEndTime = monitoringEndTime.trim();
                    if (Utils.DateUtils.isAValidDate(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime)) {
                        Date mEndTime = Utils.DateUtils.getDateFrom(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime);
                        monitoringEndTimestamp = new Timestamp(mEndTime.getTime());
                        for (String expName : mKruizeExperimentMap.keySet()) {
                            if (ServiceHelpers.KruizeObjectOperations
                                    .checkRecommendationTimestampExists(mKruizeExperimentMap.get(expName), monitoringEndTime)) {
                                kruizeObjectList.add(mKruizeExperimentMap.get(expName));
                                checkForTimestamp = true;
                            }
                        }
                        if (kruizeObjectList.isEmpty()) {
                            error = true;
                            return Response.status(Response.Status.BAD_REQUEST)
                                    .entity(new Gson().toJson(new KruizeResponse(
                                            String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.RECOMMENDATION_DOES_NOT_EXIST_MSG, monitoringEndTime),
                                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                                    .build();
                        }
                    } else {
                        error = true;
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(new Gson().toJson(new KruizeResponse(
                                        String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_TIMESTAMP_MSG, monitoringEndTime),
                                        Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                                .build();
                    }
                } else {
                    kruizeObjectList.addAll(mKruizeExperimentMap.values());
                }
            }
            if (!error) {
                statusValue = KruizeConstants.APIMessages.SUCCESS;
                return buildRecommendationsResponse(kruizeObjectList, getLatest, checkForTimestamp, monitoringEndTimestamp, Response.Status.OK.getStatusCode());
            }
        } catch (Exception e) {
            LOGGER.error("Exception in RecommendationsResource GET: {}", e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            MetricsConfig.timerGETRecommendations = MetricsConfig.timerBGETRecommendations
                    .tag("status", statusValue).register(MetricsConfig.meterRegistry());
            timer.stop(MetricsConfig.timerGETRecommendations);
        }
        return Response.ok("[]").build();
    }

    @POST
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPost(
            @QueryParam(KruizeConstants.JSONKeys.EXPERIMENT_NAME) String experimentName,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_END_TIME) String intervalEndTimeStr,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_START_TIME) String intervalStartTimeStr,
            @QueryParam(JOB_ID) String bulkJobID) {
        int calCount = counter.incrementAndGet();
        LOGGER.debug("RecommendationsResource POST request received - count: {}", calCount);
        String statusValue = KruizeConstants.APIMessages.FAILURE;
        Timer.Sample timer = Timer.start(MetricsConfig.meterRegistry());
        try {
            String target = KruizeDeploymentInfo.local ? LOCAL : REMOTE;
            if (KruizeDeploymentInfo.log_http_req_resp) {
                LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_INPUT_PARAMS,
                        experimentName, intervalStartTimeStr, intervalEndTimeStr));
            }
            RecommendationEngine recommendationEngine = new RecommendationEngine(experimentName, intervalEndTimeStr, intervalStartTimeStr);
            String errorMessage = target.equals(LOCAL) ? recommendationEngine.validate_local() : recommendationEngine.validate();
            if (errorMessage.isEmpty()) {
                KruizeObject kruizeObject = recommendationEngine.prepareRecommendations(calCount, target, bulkJobID);
                if (kruizeObject.getValidation_data().isSuccess()) {
                    Timestamp intervalEndTime = null;
                    if (intervalEndTimeStr != null) {
                        intervalEndTime = Utils.DateUtils.getTimeStampFrom(
                                KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, intervalEndTimeStr);
                        SimpleDateFormat sdf = new SimpleDateFormat(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, Locale.ROOT);
                        sdf.setTimeZone(TimeZone.getTimeZone(KruizeConstants.TimeUnitsExt.TimeZones.UTC));
                        LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_SUCCESS,
                                experimentName, sdf.format(intervalEndTime)));
                    }
                    statusValue = KruizeConstants.APIMessages.SUCCESS;
                    return buildRecommendationsResponse(List.of(kruizeObject), false, false, intervalEndTime, Response.Status.CREATED.getStatusCode());
                } else {
                    int code = kruizeObject.getValidation_data().getErrorCode();
                    return Response.status(code)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    kruizeObject.getValidation_data().getMessage(), code, "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                errorMessage, Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
        } catch (FetchMetricsError e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.generateRecommendationsAPI.ERROR_FETCHING_METRICS);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse(
                            e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Exception in RecommendationsResource POST - count: {}", calCount);
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            LOGGER.debug("RecommendationsResource POST completed - count: {}", calCount);
            MetricsConfig.timerPOSTRecommendations = MetricsConfig.timerBPOSTRecommendations
                    .tag(KruizeConstants.DataSourceConstants.DataSourceQueryJSONKeys.STATUS, statusValue)
                    .register(MetricsConfig.meterRegistry());
            timer.stop(MetricsConfig.timerPOSTRecommendations);
        }
    }

    private Response buildRecommendationsResponse(List<KruizeObject> kruizeObjectList, boolean getLatest,
                                                   boolean checkForTimestamp, Timestamp monitoringEndTimestamp, int httpStatusCode) {
        List<ListRecommendationsAPIObject> recommendationList = new ArrayList<>();
        for (KruizeObject ko : kruizeObjectList) {
            try {
                ListRecommendationsAPIObject listRec = Converters.KruizeObjectConverters
                        .convertKruizeObjectToListRecommendationSO(ko, getLatest, checkForTimestamp, monitoringEndTimestamp);
                for (KubernetesAPIObject kubernetesAPIObject : listRec.getKubernetesObjects()) {
                    if (kubernetesAPIObject.getContainerAPIObjects() != null) {
                        for (ContainerAPIObject cao : kubernetesAPIObject.getContainerAPIObjects()) {
                            ContainerRecommendations rec = cao.getContainerRecommendations();
                            if (rec != null && rec.getData() != null && !rec.getData().isEmpty())
                                processRecommendations(rec.getData().values());
                        }
                    }
                    if (kubernetesAPIObject.getNamespaceAPIObject() != null) {
                        NamespaceRecommendations rec = kubernetesAPIObject.getNamespaceAPIObject().getNamespaceRecommendations();
                        if (rec != null && rec.getData() != null && !rec.getData().isEmpty())
                            processRecommendations(rec.getData().values());
                    }
                }
                listRec.setApiVersion(CURRENT_RECOMMENDATIONS_API_VERSION);
                recommendationList.add(listRec);
            } catch (Exception e) {
                LOGGER.error("Not able to generate recommendation for expName: {} due to {}", ko.getExperimentName(), e.getMessage());
            }
        }
        ExclusionStrategy strategy = new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                return field.getDeclaringClass() == ContainerData.class && field.getName().equals(KruizeConstants.JSONKeys.RESULTS)
                        || field.getDeclaringClass() == ContainerAPIObject.class && field.getName().equals(KruizeConstants.JSONKeys.METRICS);
            }
            @Override
            public boolean shouldSkipClass(Class<?> clazz) { return false; }
        };
        String gsonStr = "[]";
        if (!recommendationList.isEmpty()) {
            gsonStr = new GsonBuilder()
                    .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .registerTypeAdapter(MetricMetadata.class, new MetricMetadataAdapter())
                    .registerTypeAdapter(MetricAggregationInfoResults.class, new MetricAggregationInfoResultsIntSerializer())
                    .setExclusionStrategies(strategy).create().toJson(recommendationList);
        }
        if (KruizeDeploymentInfo.log_http_req_resp)
            LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_RESPONSE,
                    new Gson().toJson(JsonParser.parseString(gsonStr))));
        return Response.status(httpStatusCode).entity(gsonStr).build();
    }

    private void processRecommendations(Collection<MappedRecommendationForTimestamp> recommendations) {
        if (recommendations == null) return;
        for (MappedRecommendationForTimestamp recommendation : recommendations) {
            if (recommendation == null) continue;
            restructureConfigObj(recommendation.getCurrentConfig());
            Map<String, TermRecommendations> termMap = recommendation.getRecommendationForTermHashMap();
            if (termMap != null) {
                for (TermRecommendations term : termMap.values()) {
                    if (term == null) continue;
                    Map<String, MappedRecommendationForModel> engineMap = term.getRecommendationForModelHashMap();
                    if (engineMap != null) {
                        for (MappedRecommendationForModel model : engineMap.values()) {
                            if (model == null) continue;
                            restructureConfigObj(model.getConfig());
                            restructureConfigObj(model.getVariation());
                        }
                    }
                }
            }
        }
    }

    private void restructureConfigObj(Config config) {
        if (config == null) return;
        Map<AnalyzerConstants.ResourceSetting, Map<AnalyzerConstants.RecommendationItem, RecommendationConfigItem>>
                resources = new EnumMap<>(AnalyzerConstants.ResourceSetting.class);
        Map<AnalyzerConstants.RecommendationItem, RecommendationConfigItem> requests = config.getRequests();
        if (requests != null) resources.put(AnalyzerConstants.ResourceSetting.requests, requests);
        Map<AnalyzerConstants.RecommendationItem, RecommendationConfigItem> limits = config.getLimits();
        if (limits != null) resources.put(AnalyzerConstants.ResourceSetting.limits, limits);
        if (!resources.isEmpty()) {
            config.setResources(resources);
            config.setRequests(null);
            config.setLimits(null);
        }
    }
}
