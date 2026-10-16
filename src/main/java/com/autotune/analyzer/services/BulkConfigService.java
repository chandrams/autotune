/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
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

import com.autotune.analyzer.serviceObjects.BulkConfig;
import com.autotune.analyzer.serviceObjects.BulkConfigUpdateRequest;
import com.autotune.common.bulk.BulkConfigValidation;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.database.dao.ExperimentDAO;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.table.lm.KruizeBulkConfigEntry;
import com.autotune.utils.GenericRestApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * REST API service for Bulk Config management
 * Provides CRUD operations for bulk configs and webhook notifications
 */

@Path("/bulkConfigs")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class BulkConfigService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BulkConfigService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private ExperimentDAO experimentDAO = new ExperimentDAOImpl();

    /**
     * GET /bulkConfigs - List all bulk configs or get a specific config
     * Query parameters:
     * - config_name: (optional) Get specific config by name
     * - enabled: (optional) filter by enabled=true
     */
    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam("config_name") String configName,
            @QueryParam("enabled") String enabledParam) {
        try {
            if (configName != null && !configName.trim().isEmpty()) {
                return handleGetConfig(configName);
            } else {
                return handleListConfigs(enabledParam);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing GET request: {}", e.getMessage(), e);
            return errorResponse(500, "Internal server error");
        }
    }

    /**
     * POST /bulkConfigs - Create a new bulk config
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String requestBody) {
        try {
            LOGGER.info("Received bulk config creation request: {}", requestBody);
            BulkConfig bulkConfig = objectMapper.readValue(requestBody, BulkConfig.class);

            ValidationOutputData validation = BulkConfigValidation.validateCreate(bulkConfig);
            if (!validation.isSuccess()) {
                return errorResponse(validation.getErrorCode(), validation.getMessage());
            }

            KruizeBulkConfigEntry existingConfig = experimentDAO.loadBulkConfigByName(bulkConfig.getConfigName());
            if (existingConfig != null) {
                return errorResponse(409, "Bulk config with name '" + bulkConfig.getConfigName() + "' already exists");
            }

            String now = Instant.now().toString();
            bulkConfig.setCreatedAt(now);
            bulkConfig.setUpdatedAt(now);

            KruizeBulkConfigEntry configEntry = KruizeBulkConfigEntry.fromBulkConfig(bulkConfig);
            LOGGER.info("Saving bulk config '{}' to database", bulkConfig.getConfigName());

            ValidationOutputData saveResult = experimentDAO.addBulkConfigToDB(configEntry);
            if (!saveResult.isSuccess()) {
                LOGGER.error("Failed to save bulk config '{}' to database: {}", bulkConfig.getConfigName(), saveResult.getMessage());
                return errorResponse(500, "Failed to create bulk config: " + saveResult.getMessage());
            }

            LOGGER.info("Bulk config '{}' created successfully with scheduling: {}",
                    bulkConfig.getConfigName(),
                    bulkConfig.getRecommendationSettings() != null ? bulkConfig.getRecommendationSettings().getScheduling() : null);

            return Response.status(Response.Status.CREATED).entity(objectMapper.writeValueAsString(bulkConfig)).build();
        } catch (Exception e) {
            LOGGER.error("Error creating bulk config: {}", e.getMessage(), e);
            return errorResponse(500, "Internal server error");
        }
    }

    /**
     * PUT /bulkConfigs?config_name=<name> - Update an existing bulk config
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPut(@QueryParam("config_name") String configName, String requestBody) {
        try {
            if (configName == null || configName.trim().isEmpty()) {
                return errorResponse(400, "config_name query parameter is required");
            }

            BulkConfigUpdateRequest updateRequest = objectMapper.readValue(requestBody, BulkConfigUpdateRequest.class);

            ValidationOutputData validation = BulkConfigValidation.validateUpdate(updateRequest);
            if (!validation.isSuccess()) {
                return errorResponse(validation.getErrorCode(), validation.getMessage());
            }

            KruizeBulkConfigEntry existingEntry = experimentDAO.loadBulkConfigByName(configName);
            if (existingEntry == null) {
                return errorResponse(404, "Bulk config not found: " + configName);
            }

            BulkConfig existingConfig = existingEntry.toBulkConfig();

            if (updateRequest.getClusterName() != null) existingConfig.setClusterName(updateRequest.getClusterName());
            if (updateRequest.getDatasources() != null) existingConfig.setDatasources(updateRequest.getDatasources());
            if (updateRequest.getNamespaces() != null) existingConfig.setNamespaces(updateRequest.getNamespaces());
            if (updateRequest.getLabels() != null) existingConfig.setLabels(updateRequest.getLabels());
            if (updateRequest.getExperimentTypes() != null) existingConfig.setExperimentTypes(updateRequest.getExperimentTypes());
            if (updateRequest.getMetadataProfile() != null) existingConfig.setMetadataProfile(updateRequest.getMetadataProfile());
            if (updateRequest.getPerformanceProfile() != null) existingConfig.setPerformanceProfile(updateRequest.getPerformanceProfile());

            if (updateRequest.getTrialSettings() != null) {
                BulkConfig.TrialSettings existingTrialSettings = existingConfig.getTrialSettings();
                BulkConfig.TrialSettings updateTrialSettings = updateRequest.getTrialSettings();
                if (existingTrialSettings == null) {
                    existingConfig.setTrialSettings(updateTrialSettings);
                } else {
                    if (updateTrialSettings.getMeasurementDurationMinutes() != null) {
                        existingTrialSettings.setMeasurementDurationMinutes(updateTrialSettings.getMeasurementDurationMinutes());
                    }
                    existingConfig.setTrialSettings(existingTrialSettings);
                }
            }

            if (updateRequest.getRecommendationSettings() != null) {
                BulkConfig.RecommendationSettings existingSettings = existingConfig.getRecommendationSettings();
                BulkConfig.RecommendationSettings updateSettings = updateRequest.getRecommendationSettings();
                if (existingSettings == null) {
                    existingConfig.setRecommendationSettings(updateSettings);
                } else {
                    if (updateSettings.getScheduling() != null) existingSettings.setScheduling(updateSettings.getScheduling());
                    if (updateSettings.getTerms() != null && !updateSettings.getTerms().isEmpty()) existingSettings.setTerms(updateSettings.getTerms());
                    if (updateSettings.getModels() != null && !updateSettings.getModels().isEmpty()) existingSettings.setModels(updateSettings.getModels());
                    existingConfig.setRecommendationSettings(existingSettings);
                }
            }

            if (updateRequest.getEnabled() != null) existingConfig.setEnabled(updateRequest.getEnabled());
            if (updateRequest.getWebhookUrl() != null) existingConfig.setWebhookUrl(updateRequest.getWebhookUrl());

            ValidationOutputData fullValidation = BulkConfigValidation.validateForPersist(existingConfig);
            if (!fullValidation.isSuccess()) {
                return errorResponse(fullValidation.getErrorCode(), fullValidation.getMessage());
            }

            existingConfig.setUpdatedAt(Instant.now().toString());

            KruizeBulkConfigEntry updatedEntry = KruizeBulkConfigEntry.fromBulkConfig(existingConfig);
            ValidationOutputData updateResult = experimentDAO.updateBulkConfigToDB(updatedEntry);
            if (!updateResult.isSuccess()) {
                return errorResponse(500, "Failed to update bulk config: " + updateResult.getMessage());
            }

            if (existingConfig.getWebhookUrl() != null && !existingConfig.getWebhookUrl().trim().isEmpty()) {
                triggerWebhook(existingConfig);
            }

            LOGGER.info("Updated bulk config: {}", configName);
            return Response.ok(objectMapper.writeValueAsString(existingConfig)).build();
        } catch (Exception e) {
            LOGGER.error("Error updating bulk config: {}", e.getMessage(), e);
            return errorResponse(500, "Internal server error");
        }
    }

    /**
     * DELETE /bulkConfigs?config_name=<name> - Delete a bulk config
     */
    @DELETE
    @RunOnVirtualThread
    public Response doDelete(@QueryParam("config_name") String configName) {
        try {
            if (configName == null || configName.trim().isEmpty()) {
                return errorResponse(400, "config_name query parameter is required");
            }

            KruizeBulkConfigEntry existingConfig = experimentDAO.loadBulkConfigByName(configName);
            if (existingConfig == null) {
                return errorResponse(404, "Bulk config not found: " + configName);
            }

            ValidationOutputData deleteResult = experimentDAO.deleteBulkConfigByName(configName);
            if (!deleteResult.isSuccess()) {
                return errorResponse(deleteResult.getErrorCode(), "Failed to delete bulk config: " + deleteResult.getMessage());
            }

            LOGGER.info("Deleted bulk config: {}", configName);
            return Response.ok("{\"message\":\"Bulk config deleted successfully\",\"config_name\":\"" + configName + "\"}").build();
        } catch (Exception e) {
            LOGGER.error("Error deleting bulk config: {}", e.getMessage(), e);
            return errorResponse(500, "Internal server error");
        }
    }

    private Response handleGetConfig(String configName) throws Exception {
        KruizeBulkConfigEntry configEntry = experimentDAO.loadBulkConfigByName(configName);
        if (configEntry == null) {
            return errorResponse(404, "Bulk config not found: " + configName);
        }
        BulkConfig config = configEntry.toBulkConfig();
        return Response.ok(objectMapper.writeValueAsString(config)).build();
    }

    private Response handleListConfigs(String enabledParam) throws Exception {
        List<KruizeBulkConfigEntry> configEntries;
        if (enabledParam != null && Boolean.parseBoolean(enabledParam)) {
            configEntries = experimentDAO.loadEnabledBulkConfigs();
        } else {
            configEntries = experimentDAO.loadAllBulkConfigs();
        }
        List<BulkConfig> configs = new ArrayList<>();
        for (KruizeBulkConfigEntry entry : configEntries) {
            configs.add(entry.toBulkConfig());
        }
        return Response.ok(objectMapper.writeValueAsString(configs)).build();
    }

    private void triggerWebhook(BulkConfig config) {
        try {
            String webhookUrl = config.getWebhookUrl();
            String payload = objectMapper.writeValueAsString(config);
            LOGGER.info("Triggering webhook for config: {} to URL: {}", config.getConfigName(), webhookUrl);

            GenericRestApiClient client = new GenericRestApiClient();
            client.setBaseURL(webhookUrl);
            GenericRestApiClient.HttpResponseWrapper response = client.callKruizeAPI(payload);

            int statusCode = response != null ? response.getStatusCode() : -1;
            boolean isSuccessStatus = statusCode >= 200 && statusCode < 300;
            if (isSuccessStatus) {
                LOGGER.info("Webhook triggered successfully for config: {}, status: {}", config.getConfigName(), statusCode);
            } else {
                LOGGER.warn("Webhook returned non-success status for config: {}, status: {}", config.getConfigName(), response != null ? statusCode : "null");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to trigger webhook for config: {}, error: {}", config.getConfigName(), e.getMessage(), e);
        }
    }

    private Response errorResponse(int statusCode, String message) {
        String errorJson = String.format("{\"error\":\"%s\",\"status\":%d}",
                message.replace("\"", "\\\""), statusCode);
        return Response.status(statusCode).entity(errorJson).build();
    }
}
