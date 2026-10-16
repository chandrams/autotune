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

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.RunOnVirtualThread;

import com.autotune.analyzer.adapters.DeviceDetailsAdapter;
import com.autotune.analyzer.adapters.MetricAggregationInfoResultsIntSerializer;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.FetchMetricsError;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.recommendations.engine.RecommendationEngine;
import com.autotune.analyzer.serviceObjects.ContainerAPIObject;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.metrics.MetricAggregationInfoResults;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.MetricsConfig;
import com.autotune.utils.Utils;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.autotune.utils.KruizeConstants.KRUIZE_BULK_API.JOB_ID;

/**
 *
 */

@Path("/generateRecommendations")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GenerateRecommendations {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateRecommendations.class);
    private static int requestCount = 0;

    @POST
    @RunOnVirtualThread
    public Response doPost(
            @QueryParam(KruizeConstants.JSONKeys.EXPERIMENT_NAME) String experiment_name,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_END_TIME) String intervalEndTimeStr,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_START_TIME) String intervalStartTimeStr,
            @QueryParam(JOB_ID) String bulkJobID) {
        int calCount = ++requestCount;
        LOGGER.debug("GenerateRecommendations API request count: {}", calCount);
        String statusValue = "failure";
        Timer.Sample timerBUpdateRecommendations = Timer.start(MetricsConfig.meterRegistry());
        try {
            RecommendationEngine recommendationEngine = new RecommendationEngine(experiment_name, intervalEndTimeStr, intervalStartTimeStr);
            String validationMessage = recommendationEngine.validate_local();
            if (validationMessage.isEmpty()) {
                KruizeObject kruizeObject = recommendationEngine.prepareRecommendations(calCount, AnalyzerConstants.LOCAL, bulkJobID);
                if (kruizeObject.getValidation_data().isSuccess()) {
                    LOGGER.debug("UpdateRecommendations API request count: {} success", calCount);
                    Timestamp interval_end_time = Utils.DateUtils.getTimeStampFrom(
                            KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, intervalEndTimeStr);
                    statusValue = "success";
                    return buildSuccessResponse(kruizeObject, interval_end_time);
                } else {
                    LOGGER.debug("UpdateRecommendations API request count: {} failed", calCount);
                    return Response.status(kruizeObject.getValidation_data().getErrorCode())
                            .entity("{\"error\":\"" + kruizeObject.getValidation_data().getMessage() + "\"}")
                            .build();
                }
            } else {
                LOGGER.error("Validation failed: {}", validationMessage);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + validationMessage + "\"}")
                        .build();
            }
        } catch (FetchMetricsError e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.generateRecommendationsAPI.ERROR_FETCHING_METRICS);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Exception occurred while processing request: " + e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } finally {
            if (timerBUpdateRecommendations != null) {
                MetricsConfig.timerUpdateRecomendations = MetricsConfig.timerBUpdateRecommendations
                        .tag(KruizeConstants.DataSourceConstants.DataSourceQueryJSONKeys.STATUS, statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerBUpdateRecommendations.stop(MetricsConfig.timerUpdateRecomendations);
            }
        }
    }

    private Response buildSuccessResponse(KruizeObject ko, Timestamp interval_end_time) {
        LOGGER.debug("sendSuccessResponse");
        List<ListRecommendationsAPIObject> recommendationList = new ArrayList<>();
        try {
            ListRecommendationsAPIObject listRecommendationsAPIObject = Converters.KruizeObjectConverters
                    .convertKruizeObjectToListRecommendationSO(ko, false, false, interval_end_time);
            recommendationList.add(listRecommendationsAPIObject);
        } catch (Exception e) {
            LOGGER.error("Not able to generate recommendation for expName : {} due to {}", ko.getExperimentName(), e.getMessage());
        }
        ExclusionStrategy strategy = new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                return field.getDeclaringClass() == ContainerData.class && (field.getName().equals("results"))
                        || (field.getDeclaringClass() == ContainerAPIObject.class && (field.getName().equals("metrics")));
            }

            @Override
            public boolean shouldSkipClass(Class<?> clazz) {
                return false;
            }
        };
        String gsonStr = "[]";
        if (recommendationList.size() > 0) {
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
        return Response.status(Response.Status.CREATED).entity(gsonStr).build();
    }
}
