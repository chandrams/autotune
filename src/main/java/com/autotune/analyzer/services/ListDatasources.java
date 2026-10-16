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

import com.autotune.analyzer.adapters.DataSourceInfoAdapter;
import com.autotune.analyzer.adapters.DeviceDetailsAdapter;
import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.exceptions.KruizeResponse;
import com.autotune.analyzer.serviceObjects.ListDatasourcesAPIObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.autotune.common.data.system.info.device.DeviceDetails;
import com.autotune.common.datasource.DataSourceInfo;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.MetricsConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.micrometer.core.instrument.Timer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * Rest API used to list DataSources.
 */
@Path("/datasources")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListDatasources {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListDatasources.class);

    @GET
    @RunOnVirtualThread
    public Response doGet(@QueryParam(AnalyzerConstants.ServiceConstants.DATASOURCE_NAME) String dataSourceName) {
        String statusValue = "failure";
        Timer.Sample timerListDS = Timer.start(MetricsConfig.meterRegistry());
        HashMap<String, DataSourceInfo> dataSourceMap = new HashMap<>();
        List<DataSourceInfo> dataSourceInfoList = new ArrayList<>();
        try {
            if (dataSourceName != null) {
                try {
                    DataSourceInfo dataSource = new ExperimentDBService().loadDataSourceFromDBByName(dataSourceName);
                    if (dataSource != null) dataSourceMap.put(dataSourceName, dataSource);
                } catch (Exception e) {
                    LOGGER.error("Loading saved Datasource {} failed: {} ", dataSourceName, e.getMessage());
                }
                if (!dataSourceMap.containsKey(dataSourceName)) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(new Gson().toJson(new KruizeResponse(
                                    String.format(AnalyzerErrorConstants.APIErrors.ListDataSourcesAPI.INVALID_DATASOURCE_NAME_MSG, dataSourceName),
                                    Response.Status.BAD_REQUEST.getStatusCode(), "", "ERROR")))
                            .build();
                }
                dataSourceInfoList.add(dataSourceMap.get(dataSourceName));
            } else {
                try {
                    dataSourceInfoList = new ExperimentDBService().loadAllDataSources();
                } catch (Exception e) {
                    LOGGER.error("Loading saved Datasources failed: {} ", e.getMessage());
                }
            }

            ListDatasourcesAPIObject listDatasourcesAPIObject = new ListDatasourcesAPIObject();
            listDatasourcesAPIObject.setDataSourceInfoList(dataSourceInfoList);
            String gsonStr = new GsonBuilder()
                    .disableHtmlEscaping().setPrettyPrinting().enableComplexMapKeySerialization()
                    .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
                    .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
                    .registerTypeAdapter(DeviceDetails.class, new DeviceDetailsAdapter())
                    .registerTypeAdapter(DataSourceInfo.class, new DataSourceInfoAdapter())
                    .create().toJson(listDatasourcesAPIObject);
            statusValue = "success";
            return Response.ok(gsonStr).build();

        } catch (Exception e) {
            LOGGER.error("Exception: " + e.getMessage());
            return Response.serverError()
                    .entity(new Gson().toJson(new KruizeResponse(e.getMessage(), 500, "", "ERROR")))
                    .build();
        } finally {
            if (timerListDS != null) {
                MetricsConfig.timerListDS = MetricsConfig.timerBListDS.tag("status", statusValue)
                        .register(MetricsConfig.meterRegistry());
                timerListDS.stop(MetricsConfig.timerListDS);
            }
        }
    }
}
