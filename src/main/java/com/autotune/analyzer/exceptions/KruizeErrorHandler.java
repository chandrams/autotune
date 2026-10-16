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
package com.autotune.analyzer.exceptions;

import com.autotune.analyzer.adapters.RecommendationItemAdapter;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.GsonUTCDateAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * JAX-RS ExceptionMapper that converts unhandled exceptions into JSON error responses,
 * preserving the same {@code KruizeResponse} envelope that was produced by the
 * legacy Jetty {@code KruizeErrorHandler}.
 */
@Provider
public class KruizeErrorHandler implements ExceptionMapper<Throwable> {
    private static final Logger LOGGER = LoggerFactory.getLogger(KruizeErrorHandler.class);

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .enableComplexMapKeySerialization()
            .registerTypeAdapter(Date.class, new GsonUTCDateAdapter())
            .registerTypeAdapter(AnalyzerConstants.RecommendationItem.class, new RecommendationItemAdapter())
            .create();

    @Override
    public Response toResponse(Throwable exception) {
        int status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        String message = exception.getMessage();

        if (exception instanceof jakarta.ws.rs.WebApplicationException) {
            status = ((jakarta.ws.rs.WebApplicationException) exception).getResponse().getStatus();
        }

        String gsonStr = GSON.toJson(new KruizeResponse(message, status, "", "ERROR"));

        if (status == 409) {
            LOGGER.debug(gsonStr);
        } else {
            LOGGER.error(gsonStr);
        }

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(gsonStr)
                .build();
    }
}
