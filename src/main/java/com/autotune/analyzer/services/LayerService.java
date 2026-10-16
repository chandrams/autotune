/*******************************************************************************
 * Copyright (c) 2026 Red Hat, IBM Corporation and others.
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

import com.autotune.analyzer.exceptions.MonitoringAgentNotSupportedException;
import com.autotune.analyzer.kruizeLayer.KruizeLayer;
import com.autotune.analyzer.kruizeLayer.LayerValidation;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.helper.DBHelpers;
import com.autotune.database.table.lm.KruizeLMLayerEntry;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.KruizeSupportedTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST API service for Layer management (create and list operations)
 */

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class LayerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerService.class);

    /**
     * Create a new Layer — POST /createLayer
     */
    @POST
    @Path("/createLayer")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String inputData) {
        try {
            KruizeLayer kruizeLayer = Converters.KruizeObjectConverters.convertInputJSONToCreateLayer(inputData);

            LayerValidation validation = new LayerValidation();
            ValidationOutputData validationResult = validation.validate(kruizeLayer);
            if (!validationResult.isSuccess()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Validation failed: " + validationResult.getMessage() + "\"}")
                        .build();
            }

            ExperimentDAOImpl experimentDAO = new ExperimentDAOImpl();
            List<KruizeLMLayerEntry> existingLayers = experimentDAO.loadLayerByName(kruizeLayer.getLayerName());
            if (existingLayers != null && !existingLayers.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity("{\"error\":\"" + String.format(AnalyzerErrorConstants.APIErrors.CreateLayerAPI.LAYER_ALREADY_EXISTS, kruizeLayer.getLayerName()) + "\"}")
                        .build();
            }

            KruizeLMLayerEntry layerEntry = convertToLayerEntry(kruizeLayer);
            ValidationOutputData addedToDB = experimentDAO.addLayerToDB(layerEntry);

            if (addedToDB.isSuccess()) {
                LOGGER.debug(KruizeConstants.LayerAPIMessages.ADD_LAYER_TO_DB, kruizeLayer.getLayerName());
                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("message", String.format(KruizeConstants.LayerAPIMessages.CREATE_LAYER_SUCCESS_MSG, kruizeLayer.getLayerName())
                        + KruizeConstants.LayerAPIMessages.VIEW_LAYERS_MSG);
                responseMap.put("httpcode", 201);
                responseMap.put("documentationLink", "");
                responseMap.put("status", "SUCCESS");
                return Response.status(Response.Status.CREATED).entity(new Gson().toJson(responseMap)).build();
            } else {
                return Response.serverError()
                        .entity("{\"error\":\"" + String.format(AnalyzerErrorConstants.APIErrors.CreateLayerAPI.ADD_LAYER_TO_DB_FAILURE, addedToDB.getMessage()) + "\"}")
                        .build();
            }
        } catch (MonitoringAgentNotSupportedException e) {
            LOGGER.error("Failed to create layer: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Validation failed: " + e.getMessage() + "\"}")
                    .build();
        } catch (org.json.JSONException e) {
            LOGGER.error("Invalid JSON in layer creation request: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + AnalyzerErrorConstants.APIErrors.CreateLayerAPI.INVALID_LAYER_JSON + ": " + e.getMessage() + "\"}")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Unexpected error creating layer: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity("{\"error\":\"Failed to create layer due to an internal error: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Get list of Layers — GET /listLayers
     */
    @GET
    @Path("/listLayers")
    @RunOnVirtualThread
    public Response doGet(@QueryParam("name") String layerName) {
        try {
            List<KruizeLMLayerEntry> layerEntries;
            ExperimentDAOImpl experimentDAO = new ExperimentDAOImpl();

            if (null != layerName && !layerName.isEmpty()) {
                layerEntries = experimentDAO.loadLayerByName(layerName);
            } else {
                layerEntries = experimentDAO.loadAllLayers();
            }

            if (layerName != null && layerEntries.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + String.format(AnalyzerErrorConstants.APIErrors.ListLayerAPI.INVALID_LAYER_NAME_MSG, layerName) + "\"}")
                        .build();
            } else if (null == layerName && layerEntries.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + AnalyzerErrorConstants.APIErrors.ListLayerAPI.NO_LAYERS + "\"}")
                        .build();
            }

            List<KruizeLayer> kruizeLayers = new ArrayList<>();
            for (KruizeLMLayerEntry entry : layerEntries) {
                KruizeLayer layer = DBHelpers.Converters.KruizeObjectConverters.convertLayerDBObjToLayerObject(entry);
                if (layer != null) {
                    kruizeLayers.add(layer);
                }
            }

            Gson gsonObj = createGsonObject();
            return Response.ok(gsonObj.toJson(kruizeLayers)).build();

        } catch (Exception e) {
            LOGGER.error("Failed to list layers: {}", e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + String.format(AnalyzerErrorConstants.APIErrors.ListLayerAPI.LOAD_ALL_LAYERS_ERROR, e.getMessage()) + "\"}")
                    .build();
        }
    }

    private KruizeLMLayerEntry convertToLayerEntry(KruizeLayer kruizeLayer) throws Exception {
        KruizeLMLayerEntry entry = new KruizeLMLayerEntry();
        Gson gson = new Gson();
        ObjectMapper objectMapper = new ObjectMapper();

        entry.setApi_version(kruizeLayer.getApiVersion());
        entry.setKind(kruizeLayer.getKind());

        if (kruizeLayer.getMetadata() != null) {
            String metadataJson = gson.toJson(kruizeLayer.getMetadata());
            JsonNode metadataNode = objectMapper.readTree(metadataJson);
            entry.setMetadata(metadataNode);
        }

        entry.setLayer_name(kruizeLayer.getLayerName());
        entry.setDetails(kruizeLayer.getDetails());

        if (kruizeLayer.getLayerPresence() != null) {
            String layerPresenceJson = gson.toJson(kruizeLayer.getLayerPresence());
            JsonNode layerPresenceNode = objectMapper.readTree(layerPresenceJson);
            entry.setLayer_presence(layerPresenceNode);
        }

        if (kruizeLayer.getTunables() != null) {
            String tunablesJson = gson.toJson(kruizeLayer.getTunables());
            JsonNode tunablesNode = objectMapper.readTree(tunablesJson);
            entry.setTunables(tunablesNode);
        }

        return entry;
    }

    private Gson createGsonObject() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create();
    }
}
