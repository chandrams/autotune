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
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.InvalidValueException;
import com.autotune.analyzer.exceptions.PerformanceProfileResponse;
import com.autotune.analyzer.performanceProfiles.PerformanceProfile;
import com.autotune.analyzer.performanceProfiles.utils.PerformanceProfileUtil;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.common.data.metrics.Metric;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.cache.PerformanceProfileCache;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API to create performance profile.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class PerformanceProfileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceProfileService.class);
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @POST
    @Path("/createPerformanceProfile")
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPost(String inputData) {
        try {
            Map<String, PerformanceProfile> performanceProfilesMap = new HashMap<>();
            PerformanceProfile performanceProfile = Converters.KruizeObjectConverters.convertInputJSONToCreatePerfProfile(inputData);
            ValidationOutputData validationOutputData = PerformanceProfileUtil.validateAndAddProfile(
                    performanceProfilesMap, performanceProfile, AnalyzerConstants.OperationType.CREATE);
            if (validationOutputData.isSuccess()) {
                ValidationOutputData addedToDB = new ExperimentDBService().addPerformanceProfileToDB(performanceProfile);
                if (addedToDB.isSuccess()) {
                    LOGGER.debug("Added Performance Profile : {} into the DB with version: {}",
                            performanceProfile.getName(), performanceProfile.getProfile_version());
                    return Response.status(Response.Status.CREATED)
                            .entity(gson.toJson(new PerformanceProfileResponse(
                                    String.format(KruizeConstants.APIMessages.PERFORMANCE_PROFILE_CREATE_SUCCESS, performanceProfile.getName()),
                                    Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                            .build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(gson.toJson(new PerformanceProfileResponse(
                                    addedToDB.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(validationOutputData.getErrorCode())
                        .entity(gson.toJson(new PerformanceProfileResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
        } catch (InvalidValueException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            return Response.serverError()
                    .entity(gson.toJson(new PerformanceProfileResponse(
                            "Validation failed: " + e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }

    @GET
    @Path("/listPerformanceProfiles")
    @RunOnVirtualThread
    public Response doGet() {
        String gsonStr = "[]";
        Map<String, PerformanceProfile> performanceProfilesMap = new HashMap<>();
        try {
            new ExperimentDBService().loadAllPerformanceProfiles(performanceProfilesMap);
        } catch (Exception e) {
            LOGGER.error("Failed to load saved performance profiles: {} ", e.getMessage());
        }
        if (!performanceProfilesMap.isEmpty()) {
            Collection<PerformanceProfile> values = performanceProfilesMap.values();
            Gson gsonObj = new GsonBuilder()
                    .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .setExclusionStrategies(new ExclusionStrategy() {
                        @Override
                        public boolean shouldSkipField(FieldAttributes f) {
                            return f.getDeclaringClass() == Metric.class
                                    && (f.getName().equals("trialSummaryResult") || f.getName().equals("cycleDataMap"));
                        }
                        @Override
                        public boolean shouldSkipClass(Class<?> aClass) { return false; }
                    }).create();
            gsonStr = gsonObj.toJson(values);
        } else {
            LOGGER.debug(AnalyzerErrorConstants.AutotuneObjectErrors.NO_PERF_PROFILE);
        }
        return Response.ok(gsonStr).build();
    }

    @POST
    @Path("/updatePerformanceProfile")
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPut(String inputData) {
        try {
            Map<String, PerformanceProfile> performanceProfilesMap = new HashMap<>();
            PerformanceProfile incomingPerfProfile = Converters.KruizeObjectConverters.convertInputJSONToCreatePerfProfile(inputData);
            String profileName = incomingPerfProfile.getName();
            ValidationOutputData validationOutputData = PerformanceProfileUtil.validateAndAddProfile(
                    performanceProfilesMap, incomingPerfProfile, AnalyzerConstants.OperationType.UPDATE);
            if (validationOutputData.isSuccess()) {
                ValidationOutputData updatedInDB = new ExperimentDBService().updatePerformanceProfileInDB(incomingPerfProfile);
                if (updatedInDB.isSuccess()) {
                    String msg = String.format(KruizeConstants.APIMessages.PERFORMANCE_PROFILE_UPDATE_SUCCESS,
                            profileName, incomingPerfProfile.getProfile_version());
                    LOGGER.info("{}", msg);
                    return Response.ok(gson.toJson(new PerformanceProfileResponse(msg, 200, "", "SUCCESS"))).build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(gson.toJson(new PerformanceProfileResponse(
                                    updatedInDB.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                LOGGER.debug(validationOutputData.getMessage());
                return Response.status(validationOutputData.getErrorCode())
                        .entity(gson.toJson(new PerformanceProfileResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
        } catch (InvalidValueException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            return Response.serverError()
                    .entity(gson.toJson(new PerformanceProfileResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }

    @DELETE
    @Path("/deletePerformanceProfile")
    @RunOnVirtualThread
    public Response doDelete(@QueryParam(AnalyzerConstants.ServiceConstants.PERF_PROFILE_NAME) String perfProfileName) {
        if (perfProfileName == null || perfProfileName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(gson.toJson(new PerformanceProfileResponse(
                            AnalyzerErrorConstants.AutotuneObjectErrors.MISSING_PERF_PROFILE_NAME,
                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
        try {
            Map<String, PerformanceProfile> performanceProfilesMap = new HashMap<>();
            new ExperimentDBService().loadPerformanceProfileFromDBByName(performanceProfilesMap, perfProfileName);
            if (performanceProfilesMap.get(perfProfileName) == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(gson.toJson(new PerformanceProfileResponse(
                                AnalyzerErrorConstants.AutotuneObjectErrors.MISSING_PERF_PROFILE + perfProfileName,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            Long experimentsCount = new ExperimentDBService().getExperimentsCountFromDBByProfileName(perfProfileName);
            if (experimentsCount != 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(gson.toJson(new PerformanceProfileResponse(
                                String.format(AnalyzerErrorConstants.AutotuneObjectErrors.PERF_PROFILE_EXPERIMENTS_ERROR,
                                        perfProfileName, experimentsCount, experimentsCount > 1 ? "s" : ""),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            ValidationOutputData result = new ExperimentDAOImpl().deletePerformanceProfileByName(perfProfileName);
            if (!result.isSuccess()) {
                return Response.status(result.getErrorCode())
                        .entity(gson.toJson(new PerformanceProfileResponse(
                                result.getMessage(), result.getErrorCode(), "", "ERROR")))
                        .build();
            }
            PerformanceProfileCache.remove(perfProfileName);
            return Response.ok(gson.toJson(new PerformanceProfileResponse(
                    String.format(KruizeConstants.APIMessages.PERF_PROFILE_DELETION_SUCCESS, perfProfileName),
                    200, "", "SUCCESS"))).build();
        } catch (Exception e) {
            LOGGER.error("{}", String.format(AnalyzerErrorConstants.AutotuneObjectErrors.PERF_PROFILE_DELETION_EXCEPTION,
                    perfProfileName, e.getMessage()));
            return Response.serverError()
                    .entity(gson.toJson(new PerformanceProfileResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }
}
