/*******************************************************************************
 * Copyright (c) 2025 Red Hat, IBM Corporation and others.
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
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.MetadataProfileResponse;
import com.autotune.analyzer.metadataProfiles.MetadataProfile;
import com.autotune.analyzer.metadataProfiles.MetadataProfileCollection;
import com.autotune.analyzer.metadataProfiles.utils.MetadataProfileUtil;
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
import com.autotune.utils.MetricsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class MetadataProfileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataProfileService.class);

    @POST
    @Path("/createMetadataProfile")
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPost(String inputData) {
        try {
            Map<String, MetadataProfile> metadataProfilesMap = new ConcurrentHashMap<>();
            MetadataProfile metadataProfile = Converters.KruizeObjectConverters.convertInputJSONToCreateMetadataProfile(inputData);
            ValidationOutputData validationOutputData = MetadataProfileUtil.validateAndAddMetadataProfile(metadataProfilesMap, metadataProfile);
            if (validationOutputData.isSuccess()) {
                ValidationOutputData addedToDB = new ExperimentDBService().addMetadataProfileToDB(metadataProfile);
                if (addedToDB.isSuccess()) {
                    LOGGER.debug(KruizeConstants.MetadataProfileAPIMessages.ADD_METADATA_PROFILE_TO_DB_WITH_VERSION,
                            metadataProfile.getMetadata().get(KruizeConstants.JSONKeys.NAME).asText(),
                            metadataProfile.getProfile_version());
                    MetadataProfileCollection.getInstance().addMetadataProfile(metadataProfile);
                    return Response.status(Response.Status.CREATED)
                            .entity(new Gson().toJson(new MetadataProfileResponse(
                                    String.format(KruizeConstants.MetadataProfileAPIMessages.CREATE_METADATA_PROFILE_SUCCESS_MSG,
                                            metadataProfile.getMetadata().get("name").asText())
                                            + KruizeConstants.MetadataProfileAPIMessages.VIEW_METADATA_PROFILES_MSG,
                                    Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                            .build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new MetadataProfileResponse(
                                    addedToDB.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(validationOutputData.getErrorCode())
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
        } catch (Exception e) {
            return Response.serverError()
                    .entity(new Gson().toJson(new MetadataProfileResponse(
                            KruizeConstants.MetadataProfileConstants.METADATA_PROFILE_VALIDATION_FAILURE + e.getMessage(),
                            500, "", "ERROR")))
                    .build();
        }
    }

    @GET
    @Path("/listMetadataProfiles")
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(AnalyzerConstants.MetadataProfileConstants.METADATA_PROFILE_NAME) String metadataProfileName,
            @QueryParam(AnalyzerConstants.ServiceConstants.VERBOSE) String verbose) {
        ConcurrentHashMap<String, MetadataProfile> metadataProfilesMap = new ConcurrentHashMap<>();
        boolean verboseFlag = Boolean.parseBoolean(verbose);
        try {
            loadMetadataProfilesFromCollection(metadataProfilesMap, metadataProfileName);
            if (metadataProfileName != null && !metadataProfilesMap.containsKey(metadataProfileName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.ListMetadataProfileAPI.INVALID_METADATA_PROFILE_NAME_MSG, metadataProfileName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            } else if (metadataProfileName == null && metadataProfilesMap.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                AnalyzerErrorConstants.APIErrors.ListMetadataProfileAPI.NO_METADATA_PROFILES,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            Collection<MetadataProfile> values = metadataProfilesMap.values();
            Gson gsonObj = createGsonObject();
            String gsonStr;
            if (metadataProfileName != null || verboseFlag) {
                gsonStr = gsonObj.toJson(values);
            } else {
                Collection<JsonObject> filteredValues = new ArrayList<>();
                for (MetadataProfile mp : values) {
                    JsonObject jsonObject = new JsonObject();
                    jsonObject.addProperty(KruizeConstants.JSONKeys.NAME, mp.getMetadata().get(KruizeConstants.JSONKeys.NAME).asText());
                    filteredValues.add(jsonObject);
                }
                gsonStr = gsonObj.toJson(filteredValues);
            }
            return Response.ok(gsonStr).build();
        } catch (Exception e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.ListMetadataProfileAPI.LOAD_METADATA_PROFILE_ERROR, e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new MetadataProfileResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }

    @POST
    @Path("/updateMetadataProfile")
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPut(
            @QueryParam(AnalyzerConstants.MetadataProfileConstants.METADATA_PROFILE_NAME) String metadataProfileName,
            String inputData) {
        String statusValue = "failure";
        Timer.Sample timerUpdateMetadataProfile = Timer.start(MetricsConfig.meterRegistry());
        if (metadataProfileName == null || metadataProfileName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new MetadataProfileResponse(
                            String.format(AnalyzerErrorConstants.APIErrors.UpdateMetadataProfileAPI.MISSING_METADATA_PROFILE_NAME_MSG),
                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
        if (inputData == null || inputData.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new MetadataProfileResponse(
                            AnalyzerErrorConstants.APIErrors.UpdateMetadataProfileAPI.MISSING_INPUT_PAYLOAD,
                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
        try {
            Map<String, MetadataProfile> metadataProfilesMap = new ConcurrentHashMap<>();
            loadMetadataProfilesFromCollection(metadataProfilesMap, metadataProfileName);
            if (!metadataProfilesMap.containsKey(metadataProfileName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.UpdateMetadataProfileAPI.INVALID_METADATA_PROFILE_NAME_MSG, metadataProfileName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            MetadataProfile metadataProfile = Converters.KruizeObjectConverters.convertInputJSONToCreateMetadataProfile(inputData);
            String inputMetadataProfileName = metadataProfile.getMetadata().get(KruizeConstants.JSONKeys.NAME).asText();
            if (!inputMetadataProfileName.equals(metadataProfileName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.UpdateMetadataProfileAPI.METADATA_PROFILE_NAMES_MISMATCH,
                                        metadataProfileName, inputMetadataProfileName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            ValidationOutputData validationOutputData = MetadataProfileUtil.validateMetadataProfile(metadataProfilesMap, metadataProfile);
            if (validationOutputData.isSuccess()) {
                ValidationOutputData updateResult = new ExperimentDBService().updateMetadataProfileToDB(metadataProfile);
                if (updateResult.isSuccess()) {
                    MetadataProfileCollection.getInstance().updateMetadataProfileToCollection(metadataProfileName, metadataProfile);
                    LOGGER.debug(KruizeConstants.MetadataProfileAPIMessages.UPDATE_METADATA_PROFILE_TO_DB_WITH_VERSION,
                            metadataProfile.getMetadata().get(KruizeConstants.JSONKeys.NAME).asText(), metadataProfile.getProfile_version());
                    statusValue = "success";
                    return Response.status(Response.Status.CREATED)
                            .entity(new Gson().toJson(new MetadataProfileResponse(
                                    String.format(KruizeConstants.MetadataProfileAPIMessages.UPDATE_METADATA_PROFILE_SUCCESS_MSG,
                                            metadataProfile.getMetadata().get("name").asText())
                                            + KruizeConstants.MetadataProfileAPIMessages.VIEW_METADATA_PROFILES_MSG,
                                    Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                            .build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new MetadataProfileResponse(
                                    updateResult.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(validationOutputData.getErrorCode())
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
        } catch (Exception e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.UpdateMetadataProfileAPI.UPDATE_METADATA_PROFILE_ERROR, e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new MetadataProfileResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerUpdateMetadataProfile != null) {
                MetricsConfig.timerUpdateMetadataProfile = MetricsConfig.timerBUpdateMetadataProfile
                        .tag("status", statusValue).register(MetricsConfig.meterRegistry());
                timerUpdateMetadataProfile.stop(MetricsConfig.timerUpdateMetadataProfile);
            }
        }
    }

    @DELETE
    @Path("/deleteMetadataProfile")
    @RunOnVirtualThread
    public Response doDelete(
            @QueryParam(AnalyzerConstants.MetadataProfileConstants.METADATA_PROFILE_NAME) String metadataProfileName) {
        if (metadataProfileName == null || metadataProfileName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new MetadataProfileResponse(
                            String.format(AnalyzerErrorConstants.APIErrors.DeleteMetadataProfileAPI.MISSING_METADATA_PROFILE_NAME_MSG),
                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
        try {
            ConcurrentHashMap<String, MetadataProfile> metadataProfilesMap = new ConcurrentHashMap<>();
            loadMetadataProfilesFromCollection(metadataProfilesMap, metadataProfileName);
            if (!metadataProfilesMap.containsKey(metadataProfileName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new MetadataProfileResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DeleteMetadataProfileAPI.INVALID_METADATA_PROFILE_NAME_MSG, metadataProfileName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            deleteMetadataProfile(metadataProfileName);
            return Response.status(Response.Status.CREATED)
                    .entity(new Gson().toJson(new MetadataProfileResponse(
                            String.format(KruizeConstants.MetadataProfileAPIMessages.DELETE_METADATA_PROFILE_SUCCESS_MSG, metadataProfileName)
                                    + KruizeConstants.MetadataProfileAPIMessages.VIEW_METADATA_PROFILES_MSG,
                            Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                    .build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new MetadataProfileResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }

    private void loadMetadataProfilesFromCollection(Map<String, MetadataProfile> map, String metadataProfileName) {
        try {
            MetadataProfileCollection col = MetadataProfileCollection.getInstance();
            if (metadataProfileName != null && !metadataProfileName.isEmpty()) {
                MetadataProfile mp = col.getMetadataProfileCollection().get(metadataProfileName);
                map.put(metadataProfileName, mp);
            } else {
                map.putAll(col.getMetadataProfileCollection());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load saved metadata profile data: {} ", e.getMessage());
        }
    }

    private void deleteMetadataProfile(String metadataProfileName) {
        try {
            ValidationOutputData result = new ExperimentDAOImpl().deleteKruizeLMMetadataProfileEntryByName(metadataProfileName);
            if (result.isSuccess()) {
                MetadataProfileCollection.getInstance().getMetadataProfileCollection().remove(metadataProfileName);
                LOGGER.debug(KruizeConstants.MetadataProfileAPIMessages.DELETE_METADATA_PROFILE_FROM_DB_SUCCESS_MSG);
            } else {
                LOGGER.error(AnalyzerErrorConstants.APIErrors.DeleteMetadataProfileAPI.DELETE_METADATA_PROFILE_FROM_DB_FAILURE_MSG,
                        result.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error(AnalyzerErrorConstants.APIErrors.DeleteMetadataProfileAPI.DELETE_METADATA_PROFILE_FAILURE_MSG, e.getMessage());
        }
    }

    private Gson createGsonObject() {
        return new GsonBuilder()
                .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                .registerTypeAdapter(JsonNode.class, (JsonSerializer<JsonNode>) (jsonNode, typeOfSrc, context) -> {
                    if (jsonNode instanceof ObjectNode) {
                        ObjectNode objectNode = (ObjectNode) jsonNode;
                        JsonObject metadataJson = new JsonObject();
                        if (objectNode.has("name")) metadataJson.addProperty("name", objectNode.get("name").asText());
                        return metadataJson;
                    }
                    return context.serialize(jsonNode);
                })
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        return f.getDeclaringClass() == Metric.class
                                && (f.getName().equals("trialSummaryResult") || f.getName().equals("cycleDataMap"))
                                || f.getDeclaringClass() == ContainerData.class && f.getName().equalsIgnoreCase("metrics");
                    }
                    @Override
                    public boolean shouldSkipClass(Class<?> aClass) { return false; }
                }).create();
    }
}
