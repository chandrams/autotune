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
import com.autotune.analyzer.adapters.MetricMetadataAdapter;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.FetchMetricsError;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.recommendations.engine.RecommendationEngine;
import com.autotune.analyzer.serviceObjects.ContainerAPIObject;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.metrics.MetricAggregationInfoResults;
import com.autotune.common.data.metrics.MetricMetadata;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.system.info.device.DeviceDetails;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.autotune.analyzer.utils.AnalyzerConstants.REMOTE;

/**
 * REST API to update recommendations for a given experiment.
 */
@Path("/updateRecommendations")
@RunOnVirtualThread
public class UpdateRecommendations {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateRecommendations.class);
    private static int requestCount = 0;

    @POST
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(
            @QueryParam(KruizeConstants.JSONKeys.EXPERIMENT_NAME) String experiment_name,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_END_TIME) String intervalEndTimeStr,
            @QueryParam(KruizeConstants.JSONKeys.INTERVAL_START_TIME) String intervalStartTimeStr) {
        int calCount = ++requestCount;
        LOGGER.debug(String.format(AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.UPDATE_RECOMMENDATIONS_COUNT, calCount));
        String statusValue = KruizeConstants.APIMessages.FAILURE;
        Timer.Sample timerBUpdateRecommendations = Timer.start(MetricsConfig.meterRegistry());
        if (KruizeDeploymentInfo.log_http_req_resp)
            LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_INPUT_PARAMS,
                    experiment_name, intervalStartTimeStr, intervalEndTimeStr));
        try {
            RecommendationEngine recommendationEngine = new RecommendationEngine(experiment_name, intervalEndTimeStr, intervalStartTimeStr);
            String validationMessage = recommendationEngine.validate();
            if (validationMessage.isEmpty()) {
                KruizeObject kruizeObject = recommendationEngine.prepareRecommendations(calCount, REMOTE, null);
                if (kruizeObject.getValidation_data().isSuccess()) {
                    LOGGER.debug(String.format(
                            AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.UPDATE_RECOMMENDATIONS_SUCCESS_COUNT, calCount));
                    Timestamp interval_end_time = Utils.DateUtils.getTimeStampFrom(
                            KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, intervalEndTimeStr);
                    SimpleDateFormat sdf = new SimpleDateFormat(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, Locale.ROOT);
                    sdf.setTimeZone(TimeZone.getTimeZone(KruizeConstants.TimeUnitsExt.TimeZones.UTC));
                    LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_SUCCESS,
                            experiment_name, sdf.format(interval_end_time)));
                    statusValue = KruizeConstants.APIMessages.SUCCESS;
                    return buildSuccessResponse(kruizeObject, interval_end_time);
                } else {
                    LOGGER.error(String.format(
                            AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.UPDATE_RECOMMENDATIONS_FAILED_COUNT, calCount));
                    int code = kruizeObject.getValidation_data().getErrorCode();
                    return Response.status(code)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    kruizeObject.getValidation_data().getMessage(), code, "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                validationMessage, Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
        } catch (FetchMetricsError e) {
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } catch (Exception e) {
            LOGGER.error(String.format(
                    AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.UPDATE_RECOMMENDATIONS_FAILED_COUNT, calCount));
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            LOGGER.debug(String.format(
                    AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.UPDATE_RECOMMENDATIONS_COMPLETED_COUNT, calCount));
            if (null != timerBUpdateRecommendations) {
                MetricsConfig.timerUpdateRecomendations = MetricsConfig.timerBUpdateRecommendations
                        .tag(KruizeConstants.DataSourceConstants.DataSourceQueryJSONKeys.STATUS, statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerBUpdateRecommendations.stop(MetricsConfig.timerUpdateRecomendations);
            }
        }
    }

    private Response buildSuccessResponse(KruizeObject ko, Timestamp interval_end_time) {
        List<ListRecommendationsAPIObject> recommendationList = new ArrayList<>();
        try {
            LOGGER.debug(ko.getKubernetes_objects().toString());
            ListRecommendationsAPIObject listRecommendationsAPIObject = Converters.KruizeObjectConverters
                    .convertKruizeObjectToListRecommendationSO(ko, false, false, interval_end_time);
            recommendationList.add(listRecommendationsAPIObject);
        } catch (Exception e) {
            LOGGER.error(String.format(
                    AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.GENERATE_RECOMMENDATION_FAILURE,
                    ko.getExperimentName(), e.getMessage()));
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
            Gson gsonObj = new GsonBuilder()
                    .disableHtmlEscaping()
                    .setPrettyPrinting()
                    .enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .registerTypeAdapter(MetricMetadata.class, new MetricMetadataAdapter())
                    .registerTypeAdapter(MetricAggregationInfoResults.class, new MetricAggregationInfoResultsIntSerializer())
                    .setExclusionStrategies(strategy)
                    .create();
            gsonStr = gsonObj.toJson(recommendationList);
        }
        if (KruizeDeploymentInfo.log_http_req_resp)
            LOGGER.info(String.format(KruizeConstants.APIMessages.UPDATE_RECOMMENDATIONS_RESPONSE,
                    new Gson().toJson(JsonParser.parseString(gsonStr))));
        return Response.status(Response.Status.CREATED).entity(gsonStr).build();
    }
}
