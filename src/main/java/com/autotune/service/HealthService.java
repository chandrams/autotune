/*******************************************************************************
 * Copyright (c) 2020, 2021 Red Hat, IBM Corporation and others.
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
package com.autotune.service;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/health")
@RunOnVirtualThread
public class HealthService {
    public static final int STATUS_UP = 1;
    public static final int STATUS_DOWN = 0;

    private static int CURRENT_STATUS = STATUS_UP;

    @GET
    @RunOnVirtualThread
    @Produces(MediaType.TEXT_PLAIN)
    public Response doGet() {
        if (CURRENT_STATUS == STATUS_UP) {
            return Response.ok("UP").build();
        } else {
            return Response.serverError().entity("DOWN").build();
        }
    }
}
