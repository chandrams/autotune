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
import com.autotune.analyzer.adapters.MetricMetadataAdapter;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.experiment.ExperimentInitiator;
import com.autotune.analyzer.serviceObjects.FailedUpdateResultsAPIObject;
import com.autotune.analyzer.serviceObjects.UpdateResultsAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.metrics.MetricMetadata;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.operator.KruizeDeploymentInfo;
import com.autotune.utils.MetricsConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * REST API used to receive Experiment metric results.
 */
@Path("/updateResults")
@RunOnVirtualThread
public class UpdateResults {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateResults.class);
    private static int requestCount = 0;

    @POST
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(String inputData) {
        int calCount = ++requestCount;
        LOGGER.debug("updateResults API request count: {}", calCount);
        String statusValue = "failure";
        Timer.Sample timerUpdateResults = Timer.start(MetricsConfig.meterRegistry());
        if (inputData == null) inputData = "";
        try {
            List<UpdateResultsAPIObject> updateResultsAPIObjects;
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(Double.class, new CustomNumberDeserializer())
                    .registerTypeAdapter(Integer.class, new CustomNumberDeserializer())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .registerTypeAdapter(MetricMetadata.class, new MetricMetadataAdapter())
                    .create();
            LOGGER.debug("updateResults API request payload for requestID {} is {}", calCount, inputData);
            try {
                updateResultsAPIObjects = Arrays.asList(gson.fromJson(inputData, UpdateResultsAPIObject[].class));
            } catch (JsonParseException e) {
                LOGGER.error("{} : {}", AnalyzerErrorConstants.AutotuneObjectErrors.JSON_PARSING_ERROR, e.getMessage());
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerErrorConstants.AutotuneObjectErrors.JSON_PARSING_ERROR,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            } catch (NumberFormatException e) {
                LOGGER.error("{}", e.getMessage());
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            LOGGER.debug("updateResults API request payload for requestID {} bulk count is {}", calCount, updateResultsAPIObjects.size());
            if (updateResultsAPIObjects.size() > KruizeDeploymentInfo.bulk_update_results_limit) {
                LOGGER.error(AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT_RESULTS);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT_RESULTS,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            ExperimentInitiator experimentInitiator = new ExperimentInitiator();
            experimentInitiator.validateAndAddExperimentResults(updateResultsAPIObjects);
            List<UpdateResultsAPIObject> failureAPIObjs = experimentInitiator.getFailedUpdateResultsAPIObjects();
            List<FailedUpdateResultsAPIObject> jsonObjectList = new ArrayList<>();
            if (!failureAPIObjs.isEmpty()) {
                failureAPIObjs.forEach(failObj -> jsonObjectList.add(new FailedUpdateResultsAPIObject(
                        failObj.getApiVersion(),
                        failObj.getExperimentName(),
                        failObj.getStartTimestamp(),
                        failObj.getEndTimestamp(),
                        failObj.getErrors()
                )));
                String errorMessage = String.format("Out of a total of %s records, %s failed to save",
                        updateResultsAPIObjects.size(), failureAPIObjs.size());
                LOGGER.error("updateResults API request payload for requestID {} failed", calCount);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                errorMessage, Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            } else {
                if (KruizeDeploymentInfo.log_http_req_resp)
                    LOGGER.info("updateResults API request payload for requestID {} success is {}",
                            calCount, new Gson().toJson(JsonParser.parseString(inputData)));
                statusValue = "success";
                return Response.status(Response.Status.CREATED)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerConstants.ServiceConstants.RESULT_SAVED,
                                Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                        .build();
            }
        } catch (Exception e) {
            LOGGER.error("updateResults API request payload for requestID {} failed", calCount);
            LOGGER.error("Exception: {}", e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(
                            e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            LOGGER.debug("updateResults API request payload for requestID {} completed", calCount);
            if (null != timerUpdateResults) {
                MetricsConfig.timerUpdateResults = MetricsConfig.timerBUpdateResults.tag("status", statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerUpdateResults.stop(MetricsConfig.timerUpdateResults);
            }
        }
    }

    public static class CustomNumberDeserializer implements JsonDeserializer<Number> {
        @Override
        public Number deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws NumberFormatException {
            String value = json.getAsString().trim();
            if (value.isEmpty()) {
                throw new NumberFormatException(AnalyzerErrorConstants.AutotuneObjectErrors.AGGREGATION_INFO_INVALID_VALUE);
            } else {
                return Double.parseDouble(value);
            }
        }
    }
}
