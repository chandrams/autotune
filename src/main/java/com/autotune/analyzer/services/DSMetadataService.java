/*******************************************************************************
 * Copyright (c) 2023 Red Hat, IBM Corporation and others.
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
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.metadataProfiles.MetadataProfileCollection;
import com.autotune.analyzer.serviceObjects.DSMetadataAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.common.data.dataSourceMetadata.DataSourceMetadataInfo;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.common.datasource.DataSourceInfo;
import com.autotune.common.datasource.DataSourceManager;
import com.autotune.common.datasource.DataSourceMetadataValidation;
import com.autotune.common.utils.CommonUtils;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.MetricsConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

@Path("/dsmetadata")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DSMetadataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DSMetadataService.class);

    @POST
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doPost(String inputData) {
        String statusValue = "failure";
        Timer.Sample timerImportDSMetadata = Timer.start(MetricsConfig.meterRegistry());
        HashMap<String, DataSourceMetadataInfo> dataSourceMetadataMap = new HashMap<>();
        DataSourceManager dataSourceManager = new DataSourceManager();
        if (inputData == null) inputData = "";
        try {
            if (inputData.isEmpty()) {
                throw new Exception(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_MISSING_REQUEST_INPUT_EXCPTN);
            }
            DSMetadataAPIObject metadataAPIObject = new Gson().fromJson(inputData, DSMetadataAPIObject.class);
            ValidationOutputData validationOutputData = validateMandatoryFields(metadataAPIObject);
            if (!validationOutputData.isSuccess()) {
                return Response.status(validationOutputData.getErrorCode())
                        .entity(new Gson().toJson(new KruizeResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
            String dataSourceName = metadataAPIObject.getDataSourceName();
            DataSourceInfo datasource;
            try {
                datasource = CommonUtils.getDataSourceInfo(dataSourceName);
            } catch (Exception e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_IMPORT_ERROR_MSG, dataSourceName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            String metadataProfileName = metadataAPIObject.getMetadataProfile();
            if (metadataAPIObject.getMeasurementDurationMinutes() == null || metadataAPIObject.getMeasurementDurationMinutes().isEmpty()) {
                metadataAPIObject.setMeasurementDurationMinutes(AnalyzerConstants.MetadataProfileConstants.DEFAULT_MEASUREMENT_DURATION);
            }
            Integer measurementDuration = metadataAPIObject.getMeasurement_duration_inInteger();
            if (MetadataProfileCollection.getInstance().getMetadataProfileCollection().get(metadataProfileName) == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.INVALID_METADATA_PROFILE_NAME_MSG, metadataProfileName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            DataSourceMetadataInfo metadataInfo = dataSourceManager.importMetadataFromDataSource(
                    metadataProfileName, datasource, 0, 0, 0, measurementDuration, new HashMap<>(), new HashMap<>());
            DataSourceMetadataValidation validationObject = new DataSourceMetadataValidation();
            validationObject.validate(metadataInfo);
            if (!validationObject.isSuccess()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                validationObject.getErrorMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            try {
                dataSourceManager.deleteMetadataFromDBByDataSource(datasource);
                dataSourceManager.addMetadataToDB(metadataInfo);
            } catch (Exception e) {
                return Response.serverError()
                        .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                        .build();
            }
            dataSourceMetadataMap.put(dataSourceName, metadataInfo);
            DataSourceMetadataInfo dataSourceMetadataInfo = dataSourceManager.DataSourceMetadataClusterView(dataSourceName, metadataInfo);
            if (dataSourceMetadataMap.isEmpty() || !dataSourceMetadataMap.containsKey(dataSourceName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_IMPORT_ERROR_MSG, dataSourceName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            statusValue = "success";
            return buildMetadataSuccessResponse(dataSourceMetadataInfo,
                    "View imported metadata at GET /dsmetadata");
        } catch (Exception e) {
            LOGGER.error("Unknown exception caught: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(
                            "Internal Server Error: " + e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerImportDSMetadata != null) {
                MetricsConfig.timerImportDSMetadata = MetricsConfig.timerBImportDSMetadata
                        .tag("status", statusValue).register(MetricsConfig.meterRegistry());
                timerImportDSMetadata.stop(MetricsConfig.timerImportDSMetadata);
            }
        }
    }

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(AnalyzerConstants.ServiceConstants.DATASOURCE) String dataSourceName,
            @QueryParam(AnalyzerConstants.ServiceConstants.CLUSTER_NAME) String clusterName,
            @QueryParam(AnalyzerConstants.ServiceConstants.NAMESPACE) String namespace,
            @QueryParam(AnalyzerConstants.ServiceConstants.VERBOSE) String verbose) {
        String statusValue = "failure";
        Timer.Sample timerListDSMetadata = Timer.start(MetricsConfig.meterRegistry());
        String internalVerbose = (verbose != null) ? verbose : "false";
        try {
            if (!isValidBooleanValue(internalVerbose)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.INVALID_QUERY_PARAM_VALUE,
                                        AnalyzerConstants.ServiceConstants.VERBOSE),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            if (dataSourceName == null || dataSourceName.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_NAME_MANDATORY,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            DataSourceMetadataInfo dataSourceMetadata;
            try {
                if (clusterName == null) {
                    dataSourceMetadata = new ExperimentDBService().loadMetadataFromDBByName(dataSourceName, internalVerbose);
                } else if (namespace == null) {
                    dataSourceMetadata = new ExperimentDBService().loadMetadataFromDBByClusterName(dataSourceName, clusterName, internalVerbose);
                } else {
                    dataSourceMetadata = new ExperimentDBService().loadMetadataFromDBByNamespace(dataSourceName, clusterName, namespace);
                }
            } catch (Exception e) {
                LOGGER.error("Loading saved Datasource metadata {} failed: {} ", dataSourceName, e.getMessage());
                return Response.serverError()
                        .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                        .build();
            }
            if (dataSourceMetadata == null) {
                String errorMessage;
                if (clusterName == null)
                    errorMessage = String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.INVALID_DATASOURCE_NAME_METADATA_MSG, dataSourceName);
                else if (namespace == null)
                    errorMessage = String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.INVALID_DATASOURCE_NAME_CLUSTER_NAME_METADATA_MSG, dataSourceName, clusterName);
                else
                    errorMessage = String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.MISSING_DATASOURCE_METADATA_MSG, dataSourceName, clusterName, namespace);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                errorMessage, Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            Gson gsonObj = new GsonBuilder()
                    .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .create();
            statusValue = "success";
            return Response.ok(gsonObj.toJson(dataSourceMetadata)).build();
        } catch (Exception e) {
            LOGGER.error("Exception: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerListDSMetadata != null) {
                MetricsConfig.timerListDSMetadata = MetricsConfig.timerBListDSMetadata
                        .tag("status", statusValue).register(MetricsConfig.meterRegistry());
                timerListDSMetadata.stop(MetricsConfig.timerListDSMetadata);
            }
        }
    }

    @DELETE
    @RunOnVirtualThread
    @Consumes(MediaType.APPLICATION_JSON)
    public Response doDelete(String inputData) {
        HashMap<String, DataSourceMetadataInfo> dataSourceMetadataMap = new HashMap<>();
        DataSourceManager dataSourceManager = new DataSourceManager();
        if (inputData == null) inputData = "";
        try {
            if (inputData.isEmpty()) {
                throw new Exception(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_MISSING_REQUEST_INPUT_EXCPTN);
            }
            DSMetadataAPIObject metadataAPIObject = new Gson().fromJson(inputData, DSMetadataAPIObject.class);
            ValidationOutputData validationOutputData = validateMandatoryFields(metadataAPIObject);
            if (!validationOutputData.isSuccess()) {
                return Response.status(validationOutputData.getErrorCode())
                        .entity(new Gson().toJson(new KruizeResponse(
                                validationOutputData.getMessage(), validationOutputData.getErrorCode(), "", "ERROR")))
                        .build();
            }
            String dataSourceName = metadataAPIObject.getDataSourceName();
            DataSourceInfo datasource = dataSourceManager.fetchDataSourceFromDBByName(dataSourceName);
            if (datasource == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_DELETE_ERROR_MSG, dataSourceName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            DataSourceMetadataInfo dataSourceMetadata = dataSourceManager.fetchDataSourceMetadataFromDBByName(dataSourceName, "false");
            if (dataSourceMetadata == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_DELETE_ERROR_MSG, dataSourceName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            dataSourceMetadataMap.put(dataSourceName, dataSourceMetadata);
            if (!dataSourceMetadataMap.isEmpty() && dataSourceMetadataMap.containsKey(dataSourceName)) {
                try {
                    dataSourceManager.deleteMetadataFromDB(dataSourceName);
                    dataSourceManager.deleteMetadataFromDataSource(datasource);
                    dataSourceMetadataMap.remove(dataSourceName);
                } catch (Exception e) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.DSMetadataAPI.DATASOURCE_METADATA_DELETE_ERROR_MSG, dataSourceName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }
            return Response.status(Response.Status.CREATED)
                    .entity(new Gson().toJson(new KruizeResponse(
                            KruizeConstants.DataSourceConstants.DataSourceMetadataInfoSuccessMsgs.DATASOURCE_METADATA_DELETED
                                    + " View imported metadata at GET /dsmetadata",
                            Response.Status.CREATED.getStatusCode(), "", "SUCCESS")))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse(
                            e.getMessage(), Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                    .build();
        }
    }

    private Response buildMetadataSuccessResponse(DataSourceMetadataInfo dataSourceMetadata, String suffix) {
        String gsonStr = "";
        if (dataSourceMetadata != null) {
            Gson gsonObj = new GsonBuilder()
                    .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .create();
            gsonStr = gsonObj.toJson(dataSourceMetadata);
        }
        return Response.status(Response.Status.CREATED).entity(gsonStr).build();
    }

    private List<String> mandatoryFields = new ArrayList<>(Arrays.asList(
            AnalyzerConstants.VERSION, AnalyzerConstants.DATASOURCE_NAME, AnalyzerConstants.METADATA_PROFILE
    ));

    public ValidationOutputData validateMandatoryFields(DSMetadataAPIObject metadataAPIObject) {
        List<String> missingMandatoryFields = new ArrayList<>();
        ValidationOutputData validationOutputData = new ValidationOutputData(false, null, null);
        String errorMsg = "";
        mandatoryFields.forEach(mField -> {
            String methodName = "get" + mField.substring(0, 1).toUpperCase() + mField.substring(1);
            try {
                Method getNameMethod = metadataAPIObject.getClass().getMethod(methodName);
                if (getNameMethod.invoke(metadataAPIObject) == null) missingMandatoryFields.add(mField);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                LOGGER.error("Method name for {} does not exist and the error is {}", mField, e.getMessage());
            }
        });
        if (!missingMandatoryFields.isEmpty()) {
            errorMsg = errorMsg.concat(String.format("Mandatory parameters missing %s ", missingMandatoryFields));
            validationOutputData.setErrorCode(400);
            validationOutputData.setSuccess(false);
            validationOutputData.setMessage(errorMsg);
        } else {
            validationOutputData.setSuccess(true);
        }
        return validationOutputData;
    }

    private boolean isValidBooleanValue(String value) {
        return value != null && (value.equals("true") || value.equals("false"));
    }
}
