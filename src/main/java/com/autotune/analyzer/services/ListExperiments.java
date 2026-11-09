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
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.serviceObjects.ContainerAPIObject;
import com.autotune.analyzer.serviceObjects.Converters;
import com.autotune.analyzer.serviceObjects.KubernetesAPIObject;
import com.autotune.analyzer.serviceObjects.NamespaceAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.metrics.Metric;
import com.autotune.common.data.metrics.MetricResults;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.result.IntervalResults;
import com.autotune.common.data.result.NamespaceData;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.common.k8sObjects.K8sObject;
import com.autotune.common.target.kubernetes.service.KubernetesServices;
import com.autotune.common.trials.ExperimentTrial;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.KruizeSupportedTypes;
import com.autotune.utils.MetricsConfig;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.EXPERIMENT_NAME;
import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.LATEST;

/**
 * Rest API used to list experiments.
 */
@Path("/listExperiments")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListExperiments {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListExperiments.class);

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(KruizeConstants.JSONKeys.RESULTS) String results,
            @QueryParam(LATEST) String latest,
            @QueryParam(KruizeConstants.JSONKeys.RECOMMENDATIONS) String recommendations,
            @QueryParam(EXPERIMENT_NAME) String experimentName,
            @QueryParam(AnalyzerConstants.ServiceConstants.RM) String rm) {
        String statusValue = "failure";
        Timer.Sample timerListExp = Timer.start(MetricsConfig.meterRegistry());
        boolean error = false;
        boolean rmTable = false;
        Map<String, KruizeObject> mKruizeExperimentMap = new ConcurrentHashMap<>();
        List<KubernetesAPIObject> kubernetesAPIObjectList = new ArrayList<>();
        StringBuilder clusterName = new StringBuilder();
        boolean isJSONValid = true;

        // Treat a missing param as its default
        if (results == null || results.isEmpty()) results = "false";
        if (recommendations == null || recommendations.isEmpty()) recommendations = "false";
        if (latest == null || latest.isEmpty()) latest = "true";

        if (rm != null && !rm.isEmpty() && rm.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) {
            rmTable = true;
        }

        try {
            if (!isValidBooleanValue(results) || !isValidBooleanValue(recommendations) || !isValidBooleanValue(latest)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_QUERY_PARAM_VALUE,
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }

            // Fetch experiments from DB
            if (rmTable) {
                loadExperimentsFromDatabase(mKruizeExperimentMap, experimentName);
            } else {
                loadLMExperimentsFromDatabase(mKruizeExperimentMap, experimentName);
            }

            // Check if a specific experiment was requested but not found
            if (experimentName != null && !mKruizeExperimentMap.containsKey(experimentName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new Gson().toJson(new KruizeResponse(
                                String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_EXPERIMENT_NAME_MSG, experimentName),
                                Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                        .build();
            }

            Gson gsonObj = createGsonObject();
            String gsonStr = buildResponseBasedOnQuery(mKruizeExperimentMap, gsonObj, results, recommendations, latest, experimentName, rmTable);
            if (gsonStr.isEmpty()) gsonStr = "[]";
            statusValue = "success";
            return Response.ok(gsonStr).build();

        } catch (Exception e) {
            LOGGER.error("Exception: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerListExp != null) {
                MetricsConfig.timerListExp = MetricsConfig.timerBListExp.tag("status", statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerListExp.stop(MetricsConfig.timerListExp);
            }
        }
    }

    private boolean isValidBooleanValue(String value) {
        return value != null && (value.equals("true") || value.equals("false"));
    }

    private void loadExperimentsFromDatabase(Map<String, KruizeObject> map, String experimentName) {
        try {
            if (experimentName == null || experimentName.isEmpty())
                new ExperimentDBService().loadAllExperiments(map);
            else
                new ExperimentDBService().loadExperimentFromDBByName(map, experimentName);
        } catch (Exception e) {
            LOGGER.error("Failed to load saved experiment data: {} ", e.getMessage());
        }
    }

    private void loadLMExperimentsFromDatabase(Map<String, KruizeObject> map, String experimentName) {
        try {
            if (experimentName == null || experimentName.isEmpty())
                new ExperimentDBService().loadAllLMExperiments(map);
            else
                new ExperimentDBService().loadLMExperimentFromDBByName(map, experimentName);
        } catch (Exception e) {
            LOGGER.error("Failed to load saved experiment data: {} ", e.getMessage());
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
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        return f.getDeclaringClass() == Metric.class && (
                                f.getName().equals("trialSummaryResult") || f.getName().equals("cycleDataMap"))
                                || f.getDeclaringClass() == ContainerData.class && f.getName().equalsIgnoreCase("metrics");
                    }
                    @Override
                    public boolean shouldSkipClass(Class<?> aClass) { return false; }
                })
                .create();
    }

    private String buildResponseBasedOnQuery(Map<String, KruizeObject> map, Gson gson, String results,
                                             String recommendations, String latest, String experimentName, boolean rmTable) {
        if (results.equalsIgnoreCase(AnalyzerConstants.BooleanString.FALSE)
                && recommendations.equalsIgnoreCase(AnalyzerConstants.BooleanString.FALSE)) {
            modifyJSONResponse(map, KruizeConstants.JSONKeys.RECOMMENDATIONS);
            return gson.toJson(new ArrayList<>(map.values()));
        }
        try {
            if (results.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)
                    && recommendations.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) {
                loadRecommendations(map, experimentName, rmTable);
                buildRecommendationsResponse(map, latest);
                loadResults(map, experimentName);
                if (latest.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) getLatestResults(map);
                checkPercentileInfo(map);
                return gson.toJson(new ArrayList<>(map.values()));
            } else if (results.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) {
                loadResults(map, experimentName);
                checkPercentileInfo(map);
                if (latest.equalsIgnoreCase(AnalyzerConstants.BooleanString.TRUE)) getLatestResults(map);
                modifyJSONResponse(map, KruizeConstants.JSONKeys.RECOMMENDATIONS);
                return gson.toJson(new ArrayList<>(map.values()));
            } else {
                loadRecommendations(map, experimentName, rmTable);
                buildRecommendationsResponse(map, latest);
                return gson.toJson(new ArrayList<>(map.values()));
            }
        } catch (Exception e) {
            LOGGER.error("Exception occurred while building response: {}", e.getMessage());
            return "";
        }
    }

    private void loadResults(Map<String, KruizeObject> map, String experimentName) {
        try {
            if (experimentName == null || experimentName.isEmpty())
                new ExperimentDBService().loadAllResults(map);
            else
                new ExperimentDBService().loadResultsFromDBByName(map, experimentName, null, null);
        } catch (Exception e) {
            LOGGER.error("Failed to load saved results data: {} ", e.getMessage());
        }
    }

    private void loadRecommendations(Map<String, KruizeObject> map, String experimentName, boolean rmTable) {
        try {
            if (rmTable) {
                if (experimentName == null || experimentName.isEmpty())
                    new ExperimentDBService().loadAllRecommendations(map);
                else
                    new ExperimentDBService().loadRecommendationsFromDBByName(map, experimentName);
            } else {
                if (experimentName == null || experimentName.isEmpty())
                    new ExperimentDBService().loadAllLMRecommendations(map, null);
                else
                    new ExperimentDBService().loadLMRecommendationsFromDBByName(map, experimentName, null);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load saved recommendations data: {} ", e.getMessage());
        }
    }

    private void modifyJSONResponse(Map<String, KruizeObject> map, String objectToRemove) {
        if (objectToRemove.equalsIgnoreCase("results")) {
            map.values().forEach(ko -> ko.getKubernetes_objects()
                    .forEach(cont -> cont.getContainerDataMap().values().forEach(cd -> cd.setResults(null))));
            map.values().forEach(ko -> ko.getKubernetes_objects()
                    .forEach(cont -> cont.getNamespaceDataMap().values().forEach(nd -> nd.setResults(null))));
        } else if (objectToRemove.equalsIgnoreCase("recommendations")) {
            map.values().forEach(ko -> ko.getKubernetes_objects()
                    .forEach(cont -> cont.getContainerDataMap().values().forEach(cd -> cd.setContainerRecommendations(null))));
            map.values().forEach(ko -> ko.getKubernetes_objects()
                    .forEach(cont -> cont.getNamespaceDataMap().values().forEach(nd -> nd.setNamespaceRecommendations(null))));
        }
    }

    private void getLatestResults(Map<String, KruizeObject> map) {
        try {
            for (Map.Entry<String, KruizeObject> entry : map.entrySet()) {
                for (K8sObject k8sObject : entry.getValue().getKubernetes_objects()) {
                    for (ContainerData containerData : k8sObject.getContainerDataMap().values()) {
                        if (containerData.getResults() == null) continue;
                        Converters.KruizeObjectConverters.getLatestResults(containerData);
                    }
                }
            }
        } catch (NullPointerException npe) {
            LOGGER.error("Exception occurred while fetching results: {}", npe.getMessage());
        }
    }

    private void buildRecommendationsResponse(Map<String, KruizeObject> map, String latest) {
        boolean getLatest = Boolean.parseBoolean(latest);
        for (KruizeObject ko : map.values()) {
            try {
                com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject listRec =
                        Converters.KruizeObjectConverters.convertKruizeObjectToListRecommendationSO(ko, getLatest, false, null);
                mergeRecommendationsInKruizeObject(listRec, ko);
            } catch (Exception e) {
                LOGGER.error("Not able to generate recommendation for expName: {} due to {}", ko.getExperimentName(), e.getMessage());
            }
        }
    }

    private void mergeRecommendationsInKruizeObject(com.autotune.analyzer.serviceObjects.ListRecommendationsAPIObject listRec, KruizeObject ko) {
        ko.setKubernetes_objects(convertKubernetesAPIObjectListToK8sObjectList(listRec.getKubernetesObjects()));
    }

    private static List<K8sObject> convertKubernetesAPIObjectListToK8sObjectList(List<KubernetesAPIObject> kubernetesAPIObjects) {
        List<K8sObject> k8sObjectList = new ArrayList<>();
        for (KubernetesAPIObject kao : kubernetesAPIObjects) {
            K8sObject k8sObject = new K8sObject(kao.getName(), kao.getType(), kao.getNamespace());
            HashMap<String, ContainerData> containerDataMap = new HashMap<>();
            if (kao.getContainerAPIObjects() != null) {
                for (ContainerAPIObject cao : kao.getContainerAPIObjects()) {
                    ContainerData cd = new ContainerData(cao.getContainer_name(), cao.getContainer_image_name(),
                            cao.getContainerRecommendations(), null);
                    containerDataMap.put(cao.getContainer_name(), cd);
                }
                k8sObject.setContainerDataMap(containerDataMap);
            }
            NamespaceAPIObject namespaceAPIObject = kao.getNamespaceAPIObject();
            if (namespaceAPIObject != null && namespaceAPIObject.getNamespaceRecommendations() != null) {
                NamespaceData namespaceData = new NamespaceData();
                namespaceData.setNamespace_name(namespaceAPIObject.getNamespace());
                namespaceData.setNamespaceRecommendations(namespaceAPIObject.getNamespaceRecommendations());
                HashMap<String, NamespaceData> nsMap = k8sObject.getNamespaceDataMap();
                if (nsMap == null) { nsMap = new HashMap<>(); k8sObject.setNamespaceDataMap(nsMap); }
                nsMap.put(namespaceAPIObject.getNamespace(), namespaceData);
            }
            k8sObjectList.add(k8sObject);
        }
        return k8sObjectList;
    }

    private void checkPercentileInfo(Map<String, KruizeObject> map) {
        try {
            for (Map.Entry<String, KruizeObject> entry : map.entrySet()) {
                for (K8sObject k8sObject : entry.getValue().getKubernetes_objects()) {
                    for (ContainerData containerData : k8sObject.getContainerDataMap().values()) {
                        Collection<IntervalResults> intervalResultsList;
                        try {
                            intervalResultsList = containerData.getResults().values();
                        } catch (NullPointerException npe) {
                            continue;
                        }
                        for (IntervalResults ir : intervalResultsList) {
                            for (Map.Entry<AnalyzerConstants.MetricName, MetricResults> innerEntry : ir.getMetricResultsMap().entrySet()) {
                                MetricResults metricResult = innerEntry.getValue();
                                if (!metricResult.isPercentile_results_available()) metricResult.setMetricPercentileResults(null);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Exception occurred while checking the percentile: {}", e.getMessage());
        }
    }
}
