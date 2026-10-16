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

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.RunOnVirtualThread;

import com.autotune.analyzer.adapters.DeviceDetailsAdapter;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.serviceObjects.ListSupportedK8sObjectsSO;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.utils.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;


@Path("/query/listK8sObjects")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListSupportedK8sObjects {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListSupportedK8sObjects.class);

    @POST
    @RunOnVirtualThread
    public Response doPost() {
        return buildResponse();
    }

    @GET
    @RunOnVirtualThread
    public Response doGet() {
        return buildResponse();
    }

    private Response buildResponse() {
        ListSupportedK8sObjectsSO listSupportedK8sObjectsSO = new ListSupportedK8sObjectsSO();
        for (AnalyzerConstants.K8S_OBJECT_TYPES k8SObjectType : AnalyzerConstants.K8S_OBJECT_TYPES.values()) {
            String k8sObjectTypeString = Utils.getAppropriateK8sObjectTypeString(k8SObjectType);
            if (null != k8sObjectTypeString) {
                listSupportedK8sObjectsSO.addSupportedK8sObject(k8sObjectTypeString);
            }
        }
        Gson gsonObj = new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .enableComplexMapKeySerialization()
                .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                .create();
        return Response.ok(gsonObj.toJson(listSupportedK8sObjectsSO)).build();
    }
}
