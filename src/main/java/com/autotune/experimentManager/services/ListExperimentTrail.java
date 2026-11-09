package com.autotune.experimentManager.services;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/listExperimentTrial")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ListExperimentTrail {

    @GET
    @RunOnVirtualThread
    public Response doGet() {
        return Response.ok("\"UP\"").build();
    }
}
