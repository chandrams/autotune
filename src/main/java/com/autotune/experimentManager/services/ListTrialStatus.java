/*******************************************************************************
 * Copyright (c) 2021, 2021 Red Hat, IBM Corporation and others.
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

package com.autotune.experimentManager.services;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.RunOnVirtualThread;

import com.autotune.common.trials.ExperimentTrial;
import com.autotune.common.trials.ExperimentTrialView;
import com.autotune.common.trials.TrialDetails;
import com.autotune.experimentManager.core.ExperimentTrialHandler;
import com.autotune.experimentManager.data.ExperimentDetailsMap;
import com.autotune.experimentManager.data.result.TrialMetaData;
import com.autotune.experimentManager.utils.EMConstants;
import com.autotune.experimentManager.utils.EMUtil;
import com.google.gson.Gson;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;

import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.CHARACTER_ENCODING;
import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.JSON_CONTENT_TYPE;

/**
 * This class is the handler for the endpoint `listTrialStatus`
 * Returns the status for the requested trial id
 */
@Path("/listTrialStatus")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListTrialStatus {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListTrialStatus.class);

    // Populated at startup by QuarkusStartupBean / CDI (mirrors prior servlet pattern).
    private ExperimentDetailsMap<String, ExperimentTrial> existingExperiments;

    @GET
    @RunOnVirtualThread
    public Response doGet(
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.EXPERIMENT_NAME) String experiment_name,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.TRIAL_NUM) String trial_num,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.VERBOSE) String checkVerbose,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.DEBUG) String debug) {

        boolean verbose = null != checkVerbose && checkVerbose.equalsIgnoreCase("true");

        if (null != debug) {
            HashMap<String, ExperimentTrialView> experimentTrialViews = new HashMap<>();
            this.existingExperiments.forEach((expName, experimentObj) -> {
                ExperimentTrial eObj = (ExperimentTrial) experimentObj;
                ExperimentTrialView experimentTrialView = new ExperimentTrialView();
                experimentTrialView.setStatus(eObj.getStatus());
                experimentTrialView.setCreationDate(eObj.getExperimentMetaData().getCreationDate());
                experimentTrialView.setBeginTimeStamp(eObj.getExperimentMetaData().getBeginTimestamp());
                experimentTrialView.setEndTimeStamp(eObj.getExperimentMetaData().getEndTimestamp());
                LinkedList<String> steps = new LinkedList<>();
                steps.addAll(eObj.getExperimentMetaData().getAutoTuneWorkFlow().getIterationWorkflowMap().keySet());
                experimentTrialView.setSteps(steps);
                LinkedHashMap<String, TrialMetaData> trialMetaDataLinkedHashMap = new LinkedHashMap<>();
                eObj.getTrialDetails().forEach((trialNum, trialDetails) -> {
                    trialMetaDataLinkedHashMap.put(trialNum, trialDetails.getTrialMetaData());
                });
                experimentTrialView.setTrialDetails(trialMetaDataLinkedHashMap);
                experimentTrialViews.put(eObj.getExperimentName(), experimentTrialView);
            });
            return Response.status(Response.Status.CREATED).entity(new Gson().toJson(experimentTrialViews)).build();
        }

        JSONObject responseJson = new JSONObject();
        if (null == experiment_name && null == trial_num) {
            this.existingExperiments.forEach((expName, experimentTObj) -> {
                ExperimentTrial eobj = (ExperimentTrial) experimentTObj;
                responseJson.put((String) expName, new JSONObject().put("Status", eobj.getStatus()));
            });
        } else if (null != experiment_name && null != trial_num) {
            String finalTrial_num = trial_num;
            if (verbose) {
                LOGGER.debug("In verbose");
                if (this.existingExperiments.containsKey(experiment_name)) {
                    LOGGER.debug("Experiment name found - " + experiment_name);
                    ExperimentTrial experimentTrial = (ExperimentTrial) this.existingExperiments.get(experiment_name);
                    if (experimentTrial.getTrialDetails().containsKey(trial_num)) {
                        LOGGER.debug("Response JSON before - " + responseJson.toString(2));
                        responseJson.put(finalTrial_num, EMUtil.getLiveMetricData(experimentTrial, trial_num));
                        LOGGER.debug("Response JSON after - " + responseJson.toString(2));
                    } else {
                        responseJson.put("ERROR", "Trial Number " + trial_num + " doesn't exist");
                    }
                }
            } else {
                this.existingExperiments.forEach((expName, experimentTObj) -> {
                    ExperimentTrial eobj = (ExperimentTrial) experimentTObj;
                    TrialDetails trialDetails = eobj.getTrialDetails().get(finalTrial_num);
                    JSONObject trailDetailJsonObj = new JSONObject(new Gson().toJson(trialDetails));
                    JSONObject dummyJson = ExperimentTrialHandler.getDummyMetricJson(eobj);
                    trailDetailJsonObj.put("deployments", dummyJson.get("deployments"));
                    trailDetailJsonObj.put("experiment_name", dummyJson.get("experiment_name"));
                    trailDetailJsonObj.put("deployment_name", dummyJson.get("deployment_name"));
                    responseJson.put(finalTrial_num, trailDetailJsonObj);
                });
            }
        } else if (null != experiment_name) {
            this.existingExperiments.forEach((expName, experimentTObj) -> {
                ExperimentTrial eobj = (ExperimentTrial) experimentTObj;
                ((ExperimentTrial) experimentTObj).getTrialDetails().forEach((trialNum, trialDetail) -> {
                    JSONObject trailDetailJsonObj = new JSONObject(new Gson().toJson(trialDetail));
                    JSONObject dummyJson = ExperimentTrialHandler.getDummyMetricJson(eobj);
                    trailDetailJsonObj.put("deployments", dummyJson.get("deployments"));
                    trailDetailJsonObj.put("experiment_name", dummyJson.get("experiment_name"));
                    trailDetailJsonObj.put("deployment_name", dummyJson.get("deployment_name"));
                    responseJson.put(trialNum, trailDetailJsonObj);
                });
            });
        }

        return Response.status(Response.Status.CREATED).entity(responseJson.toString()).build();
    }

    @POST
    @RunOnVirtualThread
    public Response doPost(
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.EXPERIMENT_NAME) String experiment_name,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.TRIAL_NUM) String trial_num,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.VERBOSE) String checkVerbose,
            @QueryParam(EMConstants.InputJsonKeys.ListTrialStatusKeys.DEBUG) String debug) {
        return doGet(experiment_name, trial_num, checkVerbose, debug);
    }
}
