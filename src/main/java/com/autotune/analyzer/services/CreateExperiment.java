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

import com.autotune.analyzer.exceptions.BulkNotSupportedException;
import com.autotune.analyzer.exceptions.InvalidExperimentType;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.experiment.ExperimentInitiator;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.serviceObjects.CreateExperimentAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.ServiceHelpers;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.MetricsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API to create experiments to Analyser for monitoring metrics.
 */
@Path("/createExperiment")
@RunOnVirtualThread
public class CreateExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateExperiment.class);

    @POST
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(String inputData) {
        String statusValue = "failure";
        Timer.Sample timerCreateExp = Timer.start(MetricsConfig.meterRegistry());
        Map<String, KruizeObject> mKruizeExperimentMap = new ConcurrentHashMap<>();
        if (inputData == null) inputData = "";
        try {
            List<CreateExperimentAPIObject> createExperimentAPIObjects = Arrays.asList(
                    new Gson().fromJson(inputData, CreateExperimentAPIObject[].class)
            );

            ServiceHelpers.checkForBulk(createExperimentAPIObjects);

            List<KruizeObject> kruizeExpList = ServiceHelpers.normalizeAndValidateExperimentTypes(
                    createExperimentAPIObjects
            );

            new ExperimentInitiator().validateAndAddNewExperiments(mKruizeExperimentMap, kruizeExpList);
            KruizeObject invalidKruizeObject = kruizeExpList.stream()
                    .filter(ko -> !ko.getValidation_data().isSuccess()).findAny().orElse(null);
            if (null == invalidKruizeObject) {
                ValidationOutputData addedToDB = null;
                for (KruizeObject ko : kruizeExpList) {
                    CreateExperimentAPIObject validAPIObj = createExperimentAPIObjects.stream()
                            .filter(createObj -> ko.getExperimentName().equals(createObj.getExperimentName()))
                            .findAny().orElse(null);
                    if (null != validAPIObj)
                        validAPIObj.setValidationData(ko.getValidation_data());
                    if (null != validAPIObj
                            && ko.getTarget_cluster().equalsIgnoreCase(AnalyzerConstants.LOCAL)
                            && validAPIObj.isContainerExperiment()) {
                        ServiceHelpers.detectLayers(validAPIObj);
                    }
                    addedToDB = new ExperimentDBService().addExperimentToDB(validAPIObj);
                }
                if (addedToDB.isSuccess()) {
                    statusValue = "success";
                    return Response.status(Response.Status.CREATED)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    "Experiment registered successfully with Kruize. View registered experiments at /listExperiments",
                                    Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                            .build();
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    addedToDB.getMessage(),
                                    Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                int code = invalidKruizeObject.getValidation_data().getErrorCode();
                return Response.status(code)
                        .entity(new Gson().toJson(new KruizeResponse(
                                invalidKruizeObject.getValidation_data().getMessage(), code, "", "ERROR")))
                        .build();
            }
        } catch (BulkNotSupportedException e) {
            LOGGER.error(AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse(
                            AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT,
                            Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        } catch (InvalidExperimentType | JsonParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse(
                            e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Unknown exception caught: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(
                            "Internal Server Error: " + e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (null != timerCreateExp) {
                MetricsConfig.timerCreateExp = MetricsConfig.timerBCreateExp.tag("status", statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerCreateExp.stop(MetricsConfig.timerCreateExp);
            }
        }
    }

    /**
     * TODO temp solution to delete experiments, Need to evaluate use cases
     */
    @DELETE
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doDelete(@QueryParam("rm") String rm, String inputData) {
        Map<String, KruizeObject> mKruizeExperimentMap = new ConcurrentHashMap<>();
        if (inputData == null) inputData = "";
        boolean rmTable = (null != rm && AnalyzerConstants.BooleanString.TRUE.equalsIgnoreCase(rm.trim()));
        try {
            CreateExperimentAPIObject[] createExperimentAPIObjects = new Gson().fromJson(inputData, CreateExperimentAPIObject[].class);
            if (createExperimentAPIObjects.length > 1) {
                LOGGER.error(AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerErrorConstants.AutotuneObjectErrors.UNSUPPORTED_EXPERIMENT,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            for (CreateExperimentAPIObject ko : createExperimentAPIObjects) {
                try {
                    if (rmTable) {
                        new ExperimentDBService().loadExperimentFromDBByName(mKruizeExperimentMap, ko.getExperimentName());
                    } else {
                        new ExperimentDBService().loadLMExperimentFromDBByName(mKruizeExperimentMap, ko.getExperimentName());
                    }
                } catch (Exception e) {
                    LOGGER.error("Loading saved experiment {} failed: {} ", ko.getExperimentName(), e.getMessage());
                }
            }
            for (CreateExperimentAPIObject ko : createExperimentAPIObjects) {
                String expName = ko.getExperimentName();
                if (!mKruizeExperimentMap.isEmpty() && mKruizeExperimentMap.containsKey(expName)) {
                    ValidationOutputData validationOutputData = rmTable
                            ? new ExperimentDAOImpl().deleteKruizeExperimentEntryByName(expName)
                            : new ExperimentDAOImpl().deleteKruizeLMExperimentEntryByName(expName);
                    if (validationOutputData.isSuccess()) {
                        mKruizeExperimentMap.remove(ko.getExperimentName());
                    } else {
                        throw new Exception("Experiment not deleted due to : " + validationOutputData.getMessage());
                    }
                } else {
                    throw new Exception("Experiment not found!");
                }
            }
            return Response.ok(new Gson().toJson(new KruizeResponse(
                    "Experiment deleted successfully.", 200, "", "SUCCESS"))).build();
        } catch (Exception e) {
            LOGGER.error("Exception occurred while deleting the experiment: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse(
                            e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
    }
}
