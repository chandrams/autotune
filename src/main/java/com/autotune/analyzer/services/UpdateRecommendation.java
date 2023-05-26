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

import com.autotune.analyzer.exceptions.KruizeResponse;
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
import com.autotune.analyzer.experiment.ExperimentInitiator;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.result.ExperimentResultData;
=======
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.data.result.IntervalResults;
import com.autotune.common.k8sObjects.K8sObject;
<<<<<<< HEAD
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
import com.autotune.analyzer.experiment.ExperimentInitiator;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.result.ExperimentResultData;
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
import com.autotune.analyzer.experiment.ExperimentInitiator;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.analyzer.utils.AnalyzerErrorConstants;
import com.autotune.common.data.result.ExperimentResultData;
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import com.autotune.utils.Utils;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
import java.util.Collections;
=======
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
import java.util.Collections;
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
import java.util.Collections;
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
import java.util.HashMap;
import java.util.Map;

import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.CHARACTER_ENCODING;
import static com.autotune.analyzer.utils.AnalyzerConstants.ServiceConstants.JSON_CONTENT_TYPE;

/**
 *
 */
@WebServlet(asyncSupported = true)
public class UpdateRecommendation extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateRecommendation.class);

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Get the values from the request parameters
        String experiment_name = request.getParameter(KruizeConstants.JSONKeys.EXPERIMENT_NAME);
        String intervalEndTimeStr = request.getParameter(KruizeConstants.JSONKeys.INTERVAL_END_TIME);

        // Check if experiment_name is provided
        if (experiment_name == null || experiment_name.isEmpty()) {
            sendErrorResponse(response, null, HttpServletResponse.SC_BAD_REQUEST, AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.EXPERIMENT_NAME_MANDATORY);
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
            return;
=======
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
            return;
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
            return;
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
        }

        // Check if interval_end_time is provided
        if (intervalEndTimeStr == null || intervalEndTimeStr.isEmpty()) {
            sendErrorResponse(response, null, HttpServletResponse.SC_BAD_REQUEST, AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.INTERVAL_END_TIME_MANDATORY);
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
            return;
        }

        LOGGER.debug("experiment_name : {} and interval_end_time : {}", experiment_name, intervalEndTimeStr);
<<<<<<< HEAD
<<<<<<< HEAD
=======
        }

>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
        }

>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
        // Convert interval_endtime to UTC date format
        if (!Utils.DateUtils.isAValidDate(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, intervalEndTimeStr)) {
            sendErrorResponse(
                    response,
                    new Exception(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_TIMESTAMP_EXCPTN),
                    HttpServletResponse.SC_BAD_REQUEST,
                    String.format(AnalyzerErrorConstants.APIErrors.ListRecommendationsAPI.INVALID_TIMESTAMP_MSG, intervalEndTimeStr)
            );
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
            return;
=======
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
            return;
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
            return;
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
        }

        //Check if data exist
        Timestamp interval_end_time = Utils.DateUtils.getTimeStampFrom(KruizeConstants.DateFormats.STANDARD_JSON_DATE_FORMAT, intervalEndTimeStr);
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
        ExperimentResultData experimentResultData = null;
        try {
            experimentResultData = new ExperimentDBService().getExperimentResultData(experiment_name, interval_end_time);
        } catch (Exception e) {
            sendErrorResponse(response, e, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }

        if (null != experimentResultData) {
            //Load KruizeObject and generate recommendation
            Map<String, KruizeObject> mainKruizeExperimentMAP = new HashMap<>();
            try {
                // Subtract LONG_TERM_DURATION_DAYS from the given interval_end_time
                long subtractedTime = interval_end_time.getTime() - (KruizeConstants.RecommendationEngineConstants.DurationBasedEngine.DurationAmount.LONG_TERM_DURATION_DAYS * KruizeConstants.DateFormats.MILLI_SECONDS_FOR_DAY);
                Timestamp interval_start_time = new Timestamp(subtractedTime);
                new ExperimentDBService().loadExperimentAndResultsFromDBByName(mainKruizeExperimentMAP, experiment_name, interval_start_time, interval_end_time);
                boolean recommendationCheck = new ExperimentInitiator().generateAndAddRecommendations(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                if (!recommendationCheck)
                    LOGGER.error("Failed to create recommendation for experiment: %s and interval_end_time: %s",
                            experimentResultData.getExperiment_name(),
                            experimentResultData.getIntervalEndTime());
                else {
                    boolean success = new ExperimentDBService().addRecommendationToDB(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                    if (success)
                        sendSuccessResponse(response, "Recommendation generated successfully! visit /listRecommendations");
                    else {
                        sendErrorResponse(response, null, HttpServletResponse.SC_BAD_REQUEST, AnalyzerConstants.RecommendationNotificationMsgConstant.NOT_ENOUGH_DATA);
                    }
<<<<<<< HEAD
                }
=======
        boolean dataExists = false;
=======
        ExperimentResultData experimentResultData = null;
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
        try {
            experimentResultData = new ExperimentDBService().getExperimentResultData(experiment_name, interval_end_time);
        } catch (Exception e) {
            sendErrorResponse(response, e, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }

        if (null != experimentResultData) {
            //Load KruizeObject and generate recommendation
            Map<String, KruizeObject> mainKruizeExperimentMAP = new HashMap<>();
            try {
                // Subtract LONG_TERM_DURATION_DAYS from the given interval_end_time
                long subtractedTime = interval_end_time.getTime() - (KruizeConstants.RecommendationEngineConstants.DurationBasedEngine.DurationAmount.LONG_TERM_DURATION_DAYS * KruizeConstants.DateFormats.MILLI_SECONDS_FOR_DAY);
                Timestamp interval_start_time = new Timestamp(subtractedTime);
                new ExperimentDBService().loadExperimentAndResultsFromDBByName(mainKruizeExperimentMAP, experiment_name, interval_start_time, interval_end_time);
                boolean recommendationCheck = new ExperimentInitiator().generateAndAddRecommendations(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                if (!recommendationCheck)
                    LOGGER.error("Failed to create recommendation for experiment: %s and interval_end_time: %s",
                            experimentResultData.getExperiment_name(),
                            experimentResultData.getIntervalEndTime());
                else {
                    new ExperimentDBService().addRecommendationToDB(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                    sendSuccessResponse(response, "Recommendation generated successfully! visit /listRecommendations");
=======
>>>>>>> 88f25571 (Added exception conditions.)
                }
<<<<<<< HEAD
=======
        boolean dataExists = false;
=======
        ExperimentResultData experimentResultData = null;
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
        try {
            experimentResultData = new ExperimentDBService().getExperimentResultData(experiment_name, interval_end_time);
        } catch (Exception e) {
            sendErrorResponse(response, e, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }

        if (null != experimentResultData) {
            //Load KruizeObject and generate recommendation
            Map<String, KruizeObject> mainKruizeExperimentMAP = new HashMap<>();
            try {
                // Subtract LONG_TERM_DURATION_DAYS from the given interval_end_time
                long subtractedTime = interval_end_time.getTime() - (KruizeConstants.RecommendationEngineConstants.DurationBasedEngine.DurationAmount.LONG_TERM_DURATION_DAYS * KruizeConstants.DateFormats.MILLI_SECONDS_FOR_DAY);
                Timestamp interval_start_time = new Timestamp(subtractedTime);
                new ExperimentDBService().loadExperimentAndResultsFromDBByName(mainKruizeExperimentMAP, experiment_name, interval_start_time, interval_end_time);
                boolean recommendationCheck = new ExperimentInitiator().generateAndAddRecommendations(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                if (!recommendationCheck)
                    LOGGER.error("Failed to create recommendation for experiment: %s and interval_end_time: %s",
                            experimentResultData.getExperiment_name(),
                            experimentResultData.getIntervalEndTime());
                else {
                    boolean success = new ExperimentDBService().addRecommendationToDB(mainKruizeExperimentMAP, Collections.singletonList(experimentResultData));
                    if (success)
                        sendSuccessResponse(response, "Recommendation generated successfully! visit /listRecommendations");
                    else {
                        sendErrorResponse(response, null, HttpServletResponse.SC_BAD_REQUEST, AnalyzerConstants.RecommendationNotificationMsgConstant.NOT_ENOUGH_DATA);
                    }
                }
<<<<<<< HEAD
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
//                List<ExperimentResultData> experimentResultDataList = ;
//                boolean recommendationCheck = new ExperimentInitiator().generateAndAddRecommendations(mainKruizeExperimentMAP, experimentResultDataList);
//                if (!recommendationCheck)
//                    LOGGER.error("Failed to create recommendation for experiment: %s and interval_end_time: %s",
//                            experimentResultDataList.get(0).getExperiment_name(),
//                            experimentResultDataList.get(0).getIntervalEndTime());
//                else {
//                    new ExperimentDBService().addRecommendationToDB(mKruizeExperimentMap, experimentResultDataList);
//                }
<<<<<<< HEAD
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            sendErrorResponse(response, null, HttpServletResponse.SC_BAD_REQUEST, AnalyzerErrorConstants.APIErrors.UpdateRecommendationsAPI.DATA_NOT_FOUND);
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
            return;
        }
=======
        }
        sendSuccessResponse(response, "All ok");
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
            return;
        }
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
        }
        sendSuccessResponse(response, "All ok");
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
            return;
        }
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
    }

    private void sendSuccessResponse(HttpServletResponse response, String message) throws IOException {
        response.setContentType(JSON_CONTENT_TYPE);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setStatus(HttpServletResponse.SC_CREATED);
        PrintWriter out = response.getWriter();
        out.append(
                new Gson().toJson(
                        new KruizeResponse(message, HttpServletResponse.SC_CREATED, "", "SUCCESS")
                )
        );
        out.flush();
    }

    public void sendErrorResponse(HttpServletResponse response, Exception e, int httpStatusCode, String errorMsg) throws
            IOException {
        if (null != e) {
            LOGGER.error(e.toString());
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
=======
            e.printStackTrace();
>>>>>>> 93d3e5f7 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> b0ef3a73 (UpdateRecommendation API E2E working code is ready.)
=======
            e.printStackTrace();
>>>>>>> 89b4c960 (In progress code checked in for updateRecommendation API.)
=======
>>>>>>> a670b8f5 (UpdateRecommendation API E2E working code is ready.)
            if (null == errorMsg) errorMsg = e.getMessage();
        }
        response.sendError(httpStatusCode, errorMsg);
    }
}
