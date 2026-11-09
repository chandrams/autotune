/*******************************************************************************
 * Copyright (c) 2024 Red Hat, IBM Corporation and others.
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
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.InvalidValueException;
import com.autotune.analyzer.exceptions.PerformanceProfileResponse;
import com.autotune.analyzer.performanceProfiles.MetricProfileCollection;
import com.autotune.analyzer.performanceProfiles.PerformanceProfile;
import com.autotune.analyzer.performanceProfiles.utils.PerformanceProfileUtil;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.common.data.metrics.Metric;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.KruizeSupportedTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API to create metric profile.
 */

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class MetricProfileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetricProfileService.class);

    /**
     * Validate and create new Metric Profile.
     */
    @POST
    @Path("/createMetricProfile")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String inputData) {
        try {
            Map<String, PerformanceProfile> metricProfilesMap = new ConcurrentHashMap<>();
            PerformanceProfile metricProfile = Converters.KruizeObjectConverters.convertInputJSONToCreateMetricProfile(inputData);
            ValidationOutputData validationOutputData = PerformanceProfileUtil.validateAndAddMetricProfile(metricProfilesMap, metricProfile);
            if (validationOutputData.isSuccess()) {
                ValidationOutputData addedToDB = new ExperimentDBService().addMetricProfileToDB(metricProfile);
                if (addedToDB.isSuccess()) {
                    metricProfilesMap.put(String.valueOf(metricProfile.getMetadata().get("name")), metricProfile);
                    LOGGER.debug(KruizeConstants.MetricProfileAPIMessages.ADD_METRIC_PROFILE_TO_DB_WITH_VERSION,
                            metricProfile.getMetadata().get("name").asText(), metricProfile.getProfile_version());
                    MetricProfileCollection metricProfileCollection = MetricProfileCollection.getInstance();
                    metricProfileCollection.addMetricProfile(metricProfile);
                    return Response.status(Response.Status.CREATED).entity(
                            new Gson().toJson(
                                    new PerformanceProfileResponse(
                                            String.format(KruizeConstants.MetricProfileAPIMessages.CREATE_METRIC_PROFILE_SUCCESS_MSG,
                                                    metricProfile.getMetadata().get("name").asText())
                                                    + KruizeConstants.MetricProfileAPIMessages.VIEW_METRIC_PROFILES_MSG,
                                            201, "", "SUCCESS")
                            )
                    ).build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST).entity(
                            new Gson().toJson(new PerformanceProfileResponse(addedToDB.getMessage(), 400, "", "ERROR"))
                    ).build();
                }
            } else {
                return Response.status(validationOutputData.getErrorCode()).entity(
                        new Gson().toJson(new PerformanceProfileResponse(validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR"))
                ).build();
            }
        } catch (InvalidValueException e) {
            return Response.serverError().entity(
                    new Gson().toJson(new PerformanceProfileResponse("Validation failed: " + e.getMessage(), 500, "", "ERROR"))
            ).build();
        } catch (Exception e) {
            return Response.serverError().entity(
                    new Gson().toJson(new PerformanceProfileResponse("Validation failed: " + e.getMessage(), 500, "", "ERROR"))
            ).build();
        }
    }

    /**
     * Get List of Metric Profiles
     */
    @GET
    @Path("/listMetricProfiles")
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(AnalyzerConstants.PerformanceProfileConstants.METRIC_PROFILE_NAME) String metricProfileName,
            @QueryParam(AnalyzerConstants.ServiceConstants.VERBOSE) String verbose) {
        String internalVerbose = "false";

        try {
            if (null != verbose) {
                internalVerbose = verbose;
            }

            ConcurrentHashMap<String, PerformanceProfile> metricProfilesMap = new ConcurrentHashMap<>();

            if (null != metricProfileName && !metricProfileName.isEmpty()) {
                internalVerbose = "true";
                loadMetricProfilesFromCollection(metricProfilesMap, metricProfileName);
            } else {
                loadAllMetricProfilesFromCollection(metricProfilesMap);
            }

            if (metricProfileName != null && !metricProfilesMap.containsKey(metricProfileName)) {
                return Response.status(Response.Status.BAD_REQUEST).entity(
                        new Gson().toJson(new PerformanceProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.ListMetricProfileAPI.INVALID_METRIC_PROFILE_NAME_MSG, metricProfileName),
                                400, "", "ERROR"))
                ).build();
            } else if (null == metricProfileName && metricProfilesMap.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(
                        new Gson().toJson(new PerformanceProfileResponse(
                                AnalyzerErrorConstants.APIErrors.ListMetricProfileAPI.NO_METRIC_PROFILES,
                                400, "", "ERROR"))
                ).build();
            }

            Collection<PerformanceProfile> values = metricProfilesMap.values();
            Gson gsonObj = createGsonObject();
            String gsonStr;

            if (internalVerbose.equals("false")) {
                Collection<JsonObject> filteredValues = new ArrayList<>();
                for (PerformanceProfile performanceProfile : values) {
                    JsonObject jsonObject = new JsonObject();
                    jsonObject.addProperty("name", performanceProfile.getMetadata().get("name").asText());
                    filteredValues.add(jsonObject);
                }
                gsonStr = gsonObj.toJson(filteredValues);
            } else {
                gsonStr = gsonObj.toJson(values);
            }
            return Response.ok(gsonStr).build();

        } catch (Exception e) {
            LOGGER.error(KruizeConstants.MetricProfileAPIMessages.LOAD_METRIC_PROFILE_FAILURE, e.getMessage());
            return Response.serverError().entity(
                    new Gson().toJson(new PerformanceProfileResponse(e.getMessage(), 500, "", "ERROR"))
            ).build();
        }
    }

    /**
     * Delete Metric profile — DELETE /deleteMetricProfile?name=<name>
     */
    @DELETE
    @Path("/deleteMetricProfile")
    @RunOnVirtualThread
    public Response doDelete(
            @QueryParam(AnalyzerConstants.PerformanceProfileConstants.METRIC_PROFILE_NAME) String metricProfileName) {
        if (null == metricProfileName || metricProfileName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(
                    new Gson().toJson(new PerformanceProfileResponse(
                            String.format(AnalyzerErrorConstants.APIErrors.DeleteMetricProfileAPI.MISSING_METRIC_PROFILE_NAME_MSG),
                            400, "", "ERROR"))
            ).build();
        }

        try {
            ConcurrentHashMap<String, PerformanceProfile> metricProfilesMap = new ConcurrentHashMap<>();
            loadMetricProfilesFromCollection(metricProfilesMap, metricProfileName);

            if (!metricProfilesMap.isEmpty() && metricProfilesMap.containsKey(metricProfileName)) {
                try {
                    deleteMetricProfile(metricProfileName);
                    metricProfilesMap.remove(metricProfileName);
                } catch (Exception e) {
                    return Response.status(Response.Status.BAD_REQUEST).entity(
                            new Gson().toJson(new PerformanceProfileResponse(e.getMessage(), 400, "", "ERROR"))
                    ).build();
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST).entity(
                        new Gson().toJson(new PerformanceProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DeleteMetricProfileAPI.INVALID_METRIC_PROFILE_NAME_MSG, metricProfileName),
                                400, "", "ERROR"))
                ).build();
            }

            return Response.status(Response.Status.CREATED).entity(
                    new Gson().toJson(
                            new PerformanceProfileResponse(
                                    String.format(KruizeConstants.MetricProfileAPIMessages.DELETE_METRIC_PROFILE_SUCCESS_MSG, metricProfileName)
                                            + KruizeConstants.MetricProfileAPIMessages.VIEW_METRIC_PROFILES_MSG,
                                    201, "", "SUCCESS")
                    )
            ).build();

        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(
                    new Gson().toJson(new PerformanceProfileResponse(e.getMessage(), 500, "", "ERROR"))
            ).build();
        }
    }

    private void loadMetricProfilesFromCollection(Map<String, PerformanceProfile> metricProfilesMap, String metricProfileName) {
        try {
            MetricProfileCollection metricProfileCollection = MetricProfileCollection.getInstance();
            if (null != metricProfileName && !metricProfileName.isEmpty()) {
                PerformanceProfile metricProfile = metricProfileCollection.getMetricProfileCollection().get(metricProfileName);
                metricProfilesMap.put(metricProfileName, metricProfile);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load saved metric profile data: {} ", e.getMessage());
        }
    }

    private void loadAllMetricProfilesFromCollection(Map<String, PerformanceProfile> metricProfilesMap) {
        try {
            MetricProfileCollection metricProfileCollection = MetricProfileCollection.getInstance();
            metricProfilesMap.putAll(metricProfileCollection.getMetricProfileCollection());
        } catch (Exception e) {
            LOGGER.error("Failed to load all the metric profiles data: {} ", e.getMessage());
        }
    }

    private void deleteMetricProfile(String metricProfileName) {
        try {
            ValidationOutputData deletedMetricProfileFromDB = new ExperimentDAOImpl().deleteKruizeMetricProfileEntryByName(metricProfileName);
            if (deletedMetricProfileFromDB.isSuccess()) {
                MetricProfileCollection.getInstance().getMetricProfileCollection().remove(metricProfileName);
                LOGGER.debug(KruizeConstants.MetricProfileAPIMessages.DELETE_METRIC_PROFILE_FROM_DB_SUCCESS_MSG);
            } else {
                LOGGER.error(AnalyzerErrorConstants.APIErrors.DeleteMetricProfileAPI.DELETE_METRIC_PROFILE_FROM_DB_FAILURE_MSG,
                        deletedMetricProfileFromDB.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.DeleteMetricProfileAPI.DELETE_METRIC_PROFILE_FAILURE_MSG, e.getMessage());
        }
    }

    private Gson createGsonObject() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .enableComplexMapKeySerialization()
                .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                .registerTypeAdapter(JsonNode.class, new JsonSerializer<JsonNode>() {
                    @Override
                    public JsonElement serialize(JsonNode jsonNode, Type typeOfSrc, JsonSerializationContext context) {
                        if (jsonNode instanceof ObjectNode) {
                            ObjectNode objectNode = (ObjectNode) jsonNode;
                            JsonObject metadataJson = new JsonObject();
                            if (objectNode.has("name")) {
                                metadataJson.addProperty("name", objectNode.get("name").asText());
                            }
                            return metadataJson;
                        }
                        return context.serialize(jsonNode);
                    }
                })
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        return f.getDeclaringClass() == Metric.class && (
                                f.getName().equals("trialSummaryResult")
                                        || f.getName().equals("cycleDataMap")
                        ) ||
                                f.getDeclaringClass() == ContainerData.class && (
                                        f.getName().equalsIgnoreCase("metrics")
                                );
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> aClass) {
                        return false;
                    }
                })
                .create();
    }
}
