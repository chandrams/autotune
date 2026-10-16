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

package com.autotune.analyzer.services;

import com.autotune.analyzer.adapters.DeviceDetailsAdapter;
import com.autotune.analyzer.adapters.MetricAggregationInfoResultsIntSerializer;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.serviceObjects.ContainerAPIObject;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.analyzer.utils.ServiceHelpers;
import com.autotune.common.data.metrics.MetricAggregationInfoResults;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.MetricsConfig;
import com.autotune.utils.Utils;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.autotune.utils.KruizeConstants.KRUIZE_BULK_API.JOB_ID;

/**
 * Rest API used to recommend right configuration.
 */
@Path("/listRecommendations")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListRecommendations {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListRecommendations.class);

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(AnalyzerConstants.ServiceConstants.EXPERIMENT_NAME) String experimentName,
            @QueryParam(AnalyzerConstants.ServiceConstants.LATEST) String latestRecommendation,
            @QueryParam(KruizeConstants.JSONKeys.MONITORING_END_TIME) String monitoringEndTime,
            @QueryParam(AnalyzerConstants.ServiceConstants.RM) String rm,
            @QueryParam(JOB_ID) String bulkJobID) {
        String statusValue = "failure";
        Timer.Sample timerListRec = Timer.start(MetricsConfig.meterRegistry());
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
                    if (rmTable) {
                        new ExperimentDBService().loadExperimentAndRecommendationsFromDBByName(mKruizeExperimentMap, experimentName);
                    } else {
                        new ExperimentDBService().loadLMExperimentAndRecommendationsFromDBByName(mKruizeExperimentMap, experimentName, bulkJobID);
                    }
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
                    if (rmTable) {
                        new ExperimentDBService().loadAllExperimentsAndRecommendations(mKruizeExperimentMap);
                    } else {
                        new ExperimentDBService().loadAllLMExperimentsAndRecommendations(mKruizeExperimentMap, bulkJobID);
                    }
                } catch (Exception e) {
                    LOGGER.error("Loading saved experiments failed: {} ", e.getMessage());
                }
                if (monitoringEndTime != null && !monitoringEndTime.isEmpty()) {
                    monitoringEndTime = monitoringEndTime.trim();
                    if (Utils.DateUtils.isAValidDate(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime)) {
                        Date mEndTime = Utils.DateUtils.getDateFrom(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, monitoringEndTime);
                        monitoringEndTimestamp = new Timestamp(mEndTime.getTime());
                        for (String expName : mKruizeExperimentMap.keySet()) {
                            boolean timestampExists = ServiceHelpers.KruizeObjectOperations
                                    .checkRecommendationTimestampExists(mKruizeExperimentMap.get(expName), monitoringEndTime);
                            if (timestampExists) {
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
                List<ListRecommendationsAPIObject> recommendationList = new ArrayList<>();
                for (KruizeObject ko : kruizeObjectList) {
                    try {
                        ListRecommendationsAPIObject rec = Converters.KruizeObjectConverters
                                .convertKruizeObjectToListRecommendationSO(ko, getLatest, checkForTimestamp, monitoringEndTimestamp);
                        recommendationList.add(rec);
                        statusValue = "success";
                    } catch (Exception e) {
                        LOGGER.error("Not able to generate recommendation for expName: {} due to {}",
                                ko.getExperimentName(), e.getMessage());
                    }
                }
                ExclusionStrategy strategy = new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes field) {
                        return field.getDeclaringClass() == ContainerData.class && field.getName().equals("results")
                                || field.getDeclaringClass() == ContainerAPIObject.class && field.getName().equals("metrics");
                    }
                    @Override
                    public boolean shouldSkipClass(Class<?> clazz) { return false; }
                };
                String gsonStr = "[]";
                if (!recommendationList.isEmpty()) {
                    Gson gsonObj = new GsonBuilder()
                            .disableHtmlEscaping()
                            .setPrettyPrinting()
                            .enableComplexMapKeySerialization()
                            .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                            .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                            .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                            .registerTypeAdapter(MetricAggregationInfoResults.class, new MetricAggregationInfoResultsIntSerializer())
                            .setExclusionStrategies(strategy)
                            .create();
                    gsonStr = gsonObj.toJson(recommendationList);
                }
                return Response.ok(gsonStr).build();
            }
        } catch (Exception e) {
            LOGGER.error("Exception: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerListRec != null) {
                MetricsConfig.timerListRec = MetricsConfig.timerBListRec.tag("status", statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerListRec.stop(MetricsConfig.timerListRec);
            }
        }
        // reached only when error==true path already returned above; satisfy compiler
        return Response.ok("[]").build();
    }
}
