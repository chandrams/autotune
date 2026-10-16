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

@Path("/query/listNamespaces")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListNamespaces {

    @GET
    @RunOnVirtualThread
    public Response doGet() {
        KubernetesServices kubernetesServices = null;
        try {
            kubernetesServices = new KubernetesServicesImpl();
            JSONObject returnJson = new JSONObject();
            JSONObject dataJson = new JSONObject();
            JSONArray namespacesList = new JSONArray();
            kubernetesServices.getNamespaces().forEach(namespace -> {
                namespacesList.put(namespace.getMetadata().getName());
            });
            dataJson.put(KruizeConstants.JSONKeys.NAMESPACES, namespacesList);
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
    @RunOnVirtualThread
    public Response doPost() {
        return doGet();
    }
}
