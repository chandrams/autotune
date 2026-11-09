/*******************************************************************************
 * Copyright (c) 2021, 2022 Red Hat, IBM Corporation and others.
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

import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.common.trials.ExperimentTrial;
import com.autotune.common.parallelengine.executor.KruizeExecutor;
import com.autotune.common.parallelengine.worker.KruizeWorker;
import com.autotune.common.parallelengine.worker.CallableFactory;
import com.autotune.experimentManager.data.ExperimentDetailsMap;
import com.autotune.experimentManager.data.TrialInterface;
import com.autotune.experimentManager.data.TrialInterfaceImpl;
import com.autotune.experimentManager.utils.EMConstants;
import com.autotune.experimentManager.utils.EMConstants.ParallelEngineConfigs;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;


/**
 * RestAPI endpoint used to load experiment trial in JSON format using POST method.
 * JSON format sample can be found here autotune/examples/createExperimentTrial.json
 */

@Path("/createExperimentTrial")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CreateExperimentTrial {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateExperimentTrial.class);

    // These are populated at startup by QuarkusStartupBean / CDI; kept as instance fields
    // to mirror the prior servlet pattern until a proper @ApplicationScoped bean is introduced.
    private KruizeExecutor emExecutor;
    private ExperimentDetailsMap<String, ExperimentTrial> EMExperimentTrialMap;

    /**
     * POST /createExperimentTrial — initiate experimental trials.
     * Returns HTTP 201 on success, 400 on bad input, 500 on unexpected error.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String inputData) {
        try {
            List<ExperimentTrial> experimentTrialList = Arrays.asList(new Gson().fromJson(inputData, ExperimentTrial[].class));
            TrialInterface experimentAccess = new TrialInterfaceImpl(this.EMExperimentTrialMap);
            experimentAccess.addExperiments(experimentTrialList);
            if (null != experimentAccess.getErrorMessage()) {
                return Response.status(experimentAccess.getHttpResponseCode())
                        .entity(new Gson().toJson(new KruizeResponse(experimentAccess.getErrorMessage(),
                                experimentAccess.getHttpResponseCode(), "", "ERROR")))
                        .build();
            }
            submitTask();
            return Response.status(Response.Status.CREATED)
                    .entity(new Gson().toJson(new KruizeResponse(null, 201, "", "SUCCESS")))
                    .build();
        } catch (JsonParseException e) {
            LOGGER.error(e.toString());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Gson().toJson(new KruizeResponse("Not able to parse JSON: " + e.getMessage(), 400, "", "ERROR")))
                    .build();
        } catch (Exception e) {
            LOGGER.error(e.toString());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        }
    }

    private void submitTask() {
        for (Object obj : EMExperimentTrialMap.values()) {
            try {
                ExperimentTrial experimentTrial = (ExperimentTrial) obj;
                this.emExecutor.submit(
                        new Runnable() {
                            @Override
                            public void run() {
                                KruizeWorker theWorker = new CallableFactory().create(emExecutor.getWorker());
                                theWorker.execute(null, experimentTrial, emExecutor, null);
                            }
                        }
                );
            } catch (Exception e) {
                throw e;
            }
        }
    }
}
