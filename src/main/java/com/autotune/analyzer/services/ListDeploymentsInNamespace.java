/*******************************************************************************
 * Copyright (c) 2022, 2022 Red Hat, IBM Corporation and others.
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

import com.autotune.common.target.kubernetes.service.KubernetesServices;
import com.autotune.common.target.kubernetes.service.impl.KubernetesServicesImpl;
import com.autotune.utils.KruizeConstants;
import org.json.JSONArray;
import org.json.JSONObject;

@Path("/query/listDeployments")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListDeploymentsInNamespace {

    @GET
    @RunOnVirtualThread
    public Response doGet(@QueryParam(KruizeConstants.JSONKeys.NAMESPACE) String namespace) {
        KubernetesServices kubernetesServices = null;
        try {
            if (namespace == null || namespace.isBlank()) {
                String error = KruizeConstants.ErrorMsgs.APIErrorMsgs.ListDeploymentsInNamespace.NO_NAMESPACE_SENT;
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + error + "\"}")
                        .build();
            }

            kubernetesServices = new KubernetesServicesImpl();
            JSONObject returnJson = new JSONObject();
            JSONObject dataJson = new JSONObject();
            JSONArray deploymentsList = new JSONArray();
            kubernetesServices.getDeploymentsBy(namespace).forEach(deployment -> {
                deploymentsList.put(deployment.getMetadata().getName());
            });
            dataJson.put(KruizeConstants.JSONKeys.DEPLOYMENTS, deploymentsList);
            returnJson.put(KruizeConstants.JSONKeys.DATA, dataJson);
            return Response.ok(returnJson.toString(4)).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        } finally {
            if (kubernetesServices != null) {
                kubernetesServices.shutdownClient();
            }
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response doPost(String body) {
        // Accept namespace from body JSON or fall back to empty (GET-style delegation via query param not applicable here)
        try {
            if (body != null && !body.isBlank()) {
                JSONObject inputJson = new JSONObject(body);
                if (inputJson.has(KruizeConstants.JSONKeys.NAMESPACE)) {
                    String namespace = inputJson.optString(KruizeConstants.JSONKeys.NAMESPACE, null);
                    if (namespace == null || namespace.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity("{\"error\":\"" + KruizeConstants.ErrorMsgs.APIErrorMsgs.ListDeploymentsInNamespace.EMPTY_NAMESPACE + "\"}")
                                .build();
                    }
                    return doGet(namespace);
                }
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + KruizeConstants.ErrorMsgs.APIErrorMsgs.ListDeploymentsInNamespace.NO_NAMESPACE_SENT + "\"}")
                    .build();
        } catch (Exception e) {
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
}
