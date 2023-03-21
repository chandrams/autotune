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
package com.autotune.analyzer.recommendations;

import com.autotune.analyzer.recommendations.algos.DurationBasedRecommendationSubCategory;
import com.autotune.analyzer.recommendations.algos.RecommendationSubCategory;
import com.autotune.common.data.result.IntervalResults;
import com.autotune.common.data.result.ContainerData;
import com.autotune.common.k8sObjects.DeploymentObject;
import com.autotune.analyzer.kruizeObject.KruizeObject;
import com.autotune.analyzer.utils.AnalyzerConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TODO Aggregate Function should be inline with perf profile class
 */

public class GenerateRecommendation {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateRecommendation.class);

    public static void generateRecommendation(KruizeObject kruizeObject) {
        try {
            for (String dName : kruizeObject.getDeployments().keySet()) {
                DeploymentObject deploymentObj = kruizeObject.getDeployments().get(dName);
                for (String cName : deploymentObj.getContainers().keySet()) {
                    ContainerData containerData = deploymentObj.getContainers().get(cName);
                    Timestamp monitorEndDate = containerData.getResults().keySet().stream().max(Timestamp::compareTo).get();
                    Timestamp minDate = containerData.getResults().keySet().stream().min(Timestamp::compareTo).get();
                    Timestamp monitorStartDate;
                    HashMap<String,HashMap<String, Recommendation>> recCatMap = new HashMap<String, HashMap<String, Recommendation>>();
                    for (AnalyzerConstants.RecommendationCategory recommendationCategory : AnalyzerConstants.RecommendationCategory.values()) {
                        HashMap<String, Recommendation> recommendationPeriodMap = new HashMap<>();
                        // TODO: Add all possible cases which are added in RecommendationCategory
                        switch (recommendationCategory) {
                            case DURATION_BASED:
                                for (RecommendationSubCategory recommendationSubCategory : recommendationCategory.getRecommendationSubCategories()) {
                                    DurationBasedRecommendationSubCategory durationBasedRecommendationSubCategory = (DurationBasedRecommendationSubCategory) recommendationSubCategory;
                                    String recPeriod = durationBasedRecommendationSubCategory.getSubCategory();
                                    int days = durationBasedRecommendationSubCategory.getDuration();
                                    monitorStartDate = addDays(monitorEndDate, -1 * days);
                                    if (monitorStartDate.compareTo(minDate) >= 0 || days == 1) {
                                        Timestamp finalMonitorStartDate = monitorStartDate;
                                        Map<Timestamp, IntervalResults> filteredResultsMap = containerData.getResults().entrySet().stream()
                                                .filter((x -> ((x.getKey().compareTo(finalMonitorStartDate) >= 0)
                                                        && (x.getKey().compareTo(monitorEndDate) <= 0))))
                                                .collect((Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                                        Recommendation recommendation = new Recommendation(monitorStartDate, monitorEndDate);
                                        HashMap<AnalyzerConstants.ResourceSetting, HashMap<AnalyzerConstants.RecommendationItem, RecommendationConfigItem>> config = new HashMap<>();
                                        HashMap<AnalyzerConstants.RecommendationItem, RecommendationConfigItem> requestsMap = new HashMap<>();
                                        requestsMap.put(AnalyzerConstants.RecommendationItem.cpu, getCPUCapacityRecommendation(filteredResultsMap));
                                        requestsMap.put(AnalyzerConstants.RecommendationItem.memory, getMemoryCapacityRecommendation(filteredResultsMap));
                                        config.put(AnalyzerConstants.ResourceSetting.requests, requestsMap);
                                        HashMap<AnalyzerConstants.RecommendationItem, RecommendationConfigItem> limitsMap = new HashMap<>();
                                        limitsMap.put(AnalyzerConstants.RecommendationItem.cpu, getCPUMaxRecommendation(filteredResultsMap));
                                        limitsMap.put(AnalyzerConstants.RecommendationItem.memory, getMemoryMaxRecommendation(filteredResultsMap));
                                        config.put(AnalyzerConstants.ResourceSetting.limits, limitsMap);
                                        Double hours = filteredResultsMap.values().stream().map((x) -> (x.getDurationInMinutes()))
                                                .collect(Collectors.toList())
                                                .stream()
                                                .mapToDouble(f -> f.doubleValue()).sum() / 60;
                                        recommendation.setDuration_in_hours(hours);
                                        recommendation.setConfig(config);
                                        recommendationPeriodMap.put(recPeriod, recommendation);
                                    } else {
                                        RecommendationNotification notification = new RecommendationNotification(
                                                AnalyzerConstants.RecommendationNotificationTypes.INFO.getName(),
                                                AnalyzerConstants.RecommendationNotificationMsgConstant.NOT_ENOUGH_DATA);
                                        recommendationPeriodMap.put(recPeriod, new Recommendation(notification));
                                    }
                                }
                                // This needs to be moved to common area after implementing other categories of recommedations
                                recCatMap.put(recommendationCategory.getName(), recommendationPeriodMap);
                                break;
                            case PROFILE_BASED:
                                // Need to be implemented
                                break;
                        }
                    }
                    HashMap<Timestamp, HashMap<String,HashMap<String, Recommendation>>>  containerRecommendationMap = containerData.getRecommendations();
                    if (null == containerRecommendationMap)
                        containerRecommendationMap = new HashMap<>();
                    containerRecommendationMap.put(monitorEndDate, recCatMap);
                    containerData.setRecommendations(containerRecommendationMap);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Unable to get recommendation for : {} : {}", kruizeObject.getExperimentName(), e.getMessage());
        }
    }

    private static RecommendationConfigItem getCPUCapacityRecommendation(Map<Timestamp, IntervalResults> filteredResultsMap) {
        RecommendationConfigItem recommendationConfigItem = null;
        String format = "";
        try {
            List<Double> doubleList = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getAggregationInfoResult().getSum() + e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuThrottle).getAggregationInfoResult().getSum())
                    .collect(Collectors.toList());

            for (IntervalResults intervalResults : filteredResultsMap.values()) {
                format = intervalResults.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getFormat();
                if (null != format && !format.isEmpty())
                    break;
            }
            recommendationConfigItem = new RecommendationConfigItem(percentile(0.9, doubleList), format);

        } catch (Exception e) {
            LOGGER.error("Not able to get getCPUCapacityRecommendation: " + e.getMessage());
            recommendationConfigItem = new RecommendationConfigItem(e.getMessage());
        }
        return recommendationConfigItem;
    }

    private static RecommendationConfigItem getCPUMaxRecommendation(Map<Timestamp, IntervalResults> filteredResultsMap) {
        RecommendationConfigItem recommendationConfigItem = null;
        String format = "";
        try {
            Double max_cpu = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getAggregationInfoResult().getMax() + e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuThrottle).getAggregationInfoResult().getMax())
                    .max(Double::compareTo).get();
            Double max_pods = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getAggregationInfoResult().getSum() / e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getAggregationInfoResult().getAvg())
                    .max(Double::compareTo).get();
            for (IntervalResults intervalResults : filteredResultsMap.values()) {
                format = intervalResults.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.cpuUsage).getFormat();
                if (null != format && !format.isEmpty())
                    break;
            }
            recommendationConfigItem = new RecommendationConfigItem(max_cpu * max_pods, format);
            LOGGER.debug("Max_cpu : {} , max_pods : {}", max_cpu, max_pods);
        } catch (Exception e) {
            LOGGER.error("Not able to get getCPUMaxRecommendation: " + e.getMessage());
            recommendationConfigItem = new RecommendationConfigItem(e.getMessage());
        }
        return recommendationConfigItem;

    }

    private static RecommendationConfigItem getMemoryCapacityRecommendation(Map<Timestamp, IntervalResults> filteredResultsMap) {
        RecommendationConfigItem recommendationConfigItem = null;
        String format = "";
        try {
            List<Double> doubleList = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryRSS).getAggregationInfoResult().getSum())
                    .collect(Collectors.toList());
            for (IntervalResults intervalResults : filteredResultsMap.values()) {
                format = intervalResults.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryRSS).getFormat();
                if (null != format && !format.isEmpty())
                    break;
            }
            recommendationConfigItem = new RecommendationConfigItem(percentile(0.9, doubleList), format);
        } catch (Exception e) {
            LOGGER.error("Not able to get getMemoryCapacityRecommendation: " + e.getMessage());
            recommendationConfigItem = new RecommendationConfigItem(e.getMessage());
        }
        return recommendationConfigItem;
    }

    private static RecommendationConfigItem getMemoryMaxRecommendation(Map<Timestamp, IntervalResults> filteredResultsMap) {
        RecommendationConfigItem recommendationConfigItem = null;
        String format = "";
        try {
            Double max_mem = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryUsage).getAggregationInfoResult().getMax())
                    .max(Double::compareTo).get();
            Double max_pods = filteredResultsMap.values()
                    .stream()
                    .map(e -> e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryUsage).getAggregationInfoResult().getSum() / e.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryUsage).getAggregationInfoResult().getAvg())
                    .max(Double::compareTo).get();
            for (IntervalResults intervalResults : filteredResultsMap.values()) {
                format = intervalResults.getMetricResultsMap().get(AnalyzerConstants.AggregatorType.memoryUsage).getFormat();
                if (null != format && !format.isEmpty())
                    break;
            }
            recommendationConfigItem = new RecommendationConfigItem(max_mem * max_pods, format);
            LOGGER.debug("Max_cpu : {} , max_pods : {}", max_mem, max_pods);
        } catch (Exception e) {
            LOGGER.error("Not able to get getCPUMaxRecommendation: " + e.getMessage());
            recommendationConfigItem = new RecommendationConfigItem(e.getMessage());
        }
        return recommendationConfigItem;

    }

    public static Timestamp addDays(Timestamp date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DATE, days);
        return new Timestamp(cal.getTime().getTime());
    }

    public static double percentile(double percentile, List<Double> items) {
        Collections.sort(items);
        return items.get((int) Math.round(percentile / 100.0 * (items.size() - 1)));
    }

}
