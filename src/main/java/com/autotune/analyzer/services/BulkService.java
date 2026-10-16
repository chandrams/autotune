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

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.RunOnVirtualThread;

import com.autotune.analyzer.serviceObjects.BulkInput;
import com.autotune.analyzer.serviceObjects.BulkJobStatus;
import com.autotune.analyzer.workerimpl.BulkJobManager;
import com.autotune.common.bulk.BulkServiceValidation;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.database.dao.ExperimentDAO;
import com.autotune.database.dao.ExperimentDAOImpl;
import com.autotune.database.table.lm.KruizeBulkJobEntry;
import com.autotune.operator.KruizeDeploymentInfo;
import com.autotune.utils.GenericRestApiClient;
import com.autotune.utils.MetricsConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import io.micrometer.core.instrument.Timer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.VERBOSE;
import static com.autotune.utils.KruizeConstants.KRUIZE_BULK_API.*;

/**
 *
 */

@Path("/bulk")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class BulkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BulkService.class);
    private static Map<String, BulkJobStatus> jobStatusMap = new ConcurrentHashMap<>();
    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * Filters the JSON representation of a BulkJobStatus object based on the specified include and exclude fields.
     */
    public static String filterJson(BulkJobStatus jsonInput, Set<String> includeFields, Set<String> excludeFields, String experiment_name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleFilterProvider filters = new SimpleFilterProvider();
        try {
            jsonInput.copyByPattern(experiment_name);
            if (!includeFields.isEmpty()) {
                Set<String> jobFields = new HashSet<>();
                for (String field : includeFields) {
                    if (field.startsWith(SUMMARY)) {
                        jobFields.add(SUMMARY);
                        if (field.startsWith(SUMMARY + "|")) {
                            Set<String> summaryFields = new HashSet<>();
                            for (String s : field.split("\\|")) {
                                summaryFields.add(s);
                            }
                            filters.addFilter(SUMMARY_FILTER, SimpleBeanPropertyFilter.filterOutAllExcept(summaryFields));
                        } else {
                            filters.addFilter(SUMMARY_FILTER, SimpleBeanPropertyFilter.serializeAll());
                        }
                    } else if (field.startsWith(EXPERIMENTS)) {
                        jobFields.add(EXPERIMENTS);
                        if (field.startsWith(EXPERIMENTS + "|")) {
                            Set<String> experimentFields = new HashSet<>();
                            for (String s : field.split("\\|")) {
                                experimentFields.add(s);
                            }
                            filters.addFilter(EXPERIMENTS_FILTER, SimpleBeanPropertyFilter.filterOutAllExcept(experimentFields));
                        } else {
                            filters.addFilter(EXPERIMENTS_FILTER, SimpleBeanPropertyFilter.serializeAll());
                        }
                    } else {
                        jobFields.add(field);
                    }
                }
                filters.addFilter(JOB_FILTER, SimpleBeanPropertyFilter.filterOutAllExcept(jobFields));
            } else if (!excludeFields.isEmpty()) {
                LOGGER.debug("excludeFields : {}", excludeFields);
                filters.addFilter(JOB_FILTER, SimpleBeanPropertyFilter.serializeAllExcept(excludeFields));
            }

            mapper.setFilterProvider(filters);
        } catch (Exception e) {
            LOGGER.error("Not able to filter experiments due to {}", e.getMessage());
            e.printStackTrace();
        } finally {
            synchronized (jsonInput) {
                return mapper.writeValueAsString(jsonInput);
            }
        }
    }

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(JOB_ID) String jobID,
            @QueryParam(VERBOSE) String verboseParam,
            @QueryParam("include") String includeParams,
            @QueryParam("exclude") String excludeParams,
            @QueryParam("experiment_name") String experiment_name) {
        String statusValue = "failure";
        Timer.Sample timerJobStatus = Timer.start(MetricsConfig.meterRegistry());
        try {
            Set<String> includeFields = includeParams != null ? new HashSet<>(Arrays.asList(includeParams.split(","))) : new HashSet<>(Arrays.asList("summary"));
            Set<String> excludeFields = excludeParams != null ? new HashSet<>(Arrays.asList(excludeParams.split(","))) : Collections.emptySet();
            boolean verbose = verboseParam != null && Boolean.parseBoolean(verboseParam);

            BulkJobStatus jobDetails;
            if (KruizeDeploymentInfo.test_use_only_cache_job_in_mem) {
                if (jobStatusMap.isEmpty()) {
                    return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"" + JOB_NOT_FOUND_MSG + "\"}").build();
                }
                jobDetails = jobStatusMap.get(jobID);
            } else {
                ExperimentDAO experimentDAO = new ExperimentDAOImpl();
                KruizeBulkJobEntry kruizeBulkJobEntry = experimentDAO.findBulkJobById(jobID);
                if (null == kruizeBulkJobEntry) {
                    return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"" + JOB_NOT_FOUND_MSG + "\"}").build();
                }
                jobDetails = kruizeBulkJobEntry.getBulkJobStatus();

                GenericRestApiClient recommendationApiClient = new GenericRestApiClient();
                String listRecommendationsURL = String.format(
                        KruizeDeploymentInfo.recommendations_url.replaceAll("generateRecommendations.*", "listRecommendations") + "?" + JOB_ID
                                + "=%s", jobID);
                if (experiment_name != null && !experiment_name.equals("")) {
                    String encodedExperimentName = URLEncoder.encode(experiment_name, StandardCharsets.UTF_8);
                    listRecommendationsURL = listRecommendationsURL + "&experiment_name=" + encodedExperimentName;
                }
                recommendationApiClient.setBaseURL(listRecommendationsURL);
                Map<String, JsonNode> recommendationResponse = new HashMap<>();
                try {
                    GenericRestApiClient.HttpResponseWrapper recommendationResponseCode = recommendationApiClient.getKruizeAPI(null);
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode rootArray = objectMapper.readTree(recommendationResponseCode.getResponseBody().toString());
                    for (JsonNode node : rootArray) {
                        String experimentName = node.get("experiment_name").asText();
                        recommendationResponse.put(experimentName, node);
                    }
                    if (!includeFields.isEmpty() && includeFields.contains("experiments")) {
                        if (jobDetails != null && jobDetails.getExperimentMap() != null) {
                            jobDetails.getExperimentMap().forEach(
                                    (experimentName, experiment) -> {
                                        BulkJobStatus.GenerateRecommendationsAPIResponse bresp =
                                                new BulkJobStatus.GenerateRecommendationsAPIResponse();
                                        if (recommendationResponse.containsKey(experimentName)) {
                                            bresp.setResponse(
                                                    new ArrayList<>(Arrays.asList(recommendationResponse.get(experimentName)))
                                            );
                                            experiment.getApis().setRecommendations(bresp);
                                        }
                                    }
                            );
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Not able to fetch recommedations from database due to {}", e.getMessage());
                }
            }

            if (jobDetails == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"" + JOB_NOT_FOUND_MSG + "\"}").build();
            }

            String filteredJson = filterJson(jobDetails, includeFields, excludeFields, experiment_name);
            statusValue = "success";
            return Response.ok(filteredJson).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        } finally {
            if (null != timerJobStatus) {
                MetricsConfig.timerJobStatus = MetricsConfig.timerBJobStatus.tag("status", statusValue).register(MetricsConfig.meterRegistry());
                timerJobStatus.stop(MetricsConfig.timerJobStatus);
            }
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String body) {
        String statusValue = "failure";
        Timer.Sample timerCreateBulkJob = Timer.start(MetricsConfig.meterRegistry());
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            BulkInput payload = objectMapper.readValue(body, BulkInput.class);

            String jobID = UUID.randomUUID().toString();
            if (payload != null && !payload.isEmpty()) {
                ValidationOutputData validationOutputData = BulkServiceValidation.validate(payload, jobID);
                if (!validationOutputData.isSuccess()) {
                    throw new Exception(validationOutputData.getMessage());
                }
            }
            BulkJobStatus jobStatus = new BulkJobStatus(jobID, IN_PROGRESS, Instant.now(), payload);

            if (KruizeDeploymentInfo.test_use_only_cache_job_in_mem)
                jobStatusMap.put(jobID, jobStatus);
            else {
                try {
                    new ExperimentDAOImpl().bulkJobSave(jobStatus.getBulkJobForDB("{}"));
                } catch (Exception e) {
                    LOGGER.error("Not able to save job details into DB {} due to {}", jobStatus, e.getMessage());
                }
            }
            executorService.submit(new BulkJobManager(jobID, jobStatus, payload));

            JSONObject jsonObject = new JSONObject();
            jsonObject.put(JOB_ID, jobID);
            statusValue = "success";
            return Response.ok(jsonObject.toString()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        } finally {
            if (null != timerCreateBulkJob) {
                MetricsConfig.timerCreateBulkJob = MetricsConfig.timerBCreateBulkJob.tag("status", statusValue).register(MetricsConfig.meterRegistry());
                timerCreateBulkJob.stop(MetricsConfig.timerCreateBulkJob);
            }
        }
    }
}
