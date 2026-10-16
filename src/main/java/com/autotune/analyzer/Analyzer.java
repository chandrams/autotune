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
package com.autotune.analyzer;

import com.autotune.analyzer.metadataProfiles.MetadataProfileDeployment;
import com.autotune.analyzer.performanceProfiles.PerformanceProfilesDeployment;
import com.autotune.operator.KruizeDeploymentInfo;
import com.autotune.operator.KruizeOperator;

/**
 * Analyzer startup helper — loads performance/metadata profiles and KruizeObjects.
 * Servlet registration has been removed; all endpoints are wired via JAX-RS {@code @Path}.
 * Called from {@link com.autotune.operator.KruizeStartupBean} on {@code StartupEvent}.
 */
public class Analyzer {

    private Analyzer() {}

    public static void start() {
        KruizeOperator kruizeOperator = new KruizeOperator();
        try {
            PerformanceProfilesDeployment.getPerformanceProfiles(); //  Performance profile should be called first
            if (KruizeDeploymentInfo.local) {
                MetadataProfileDeployment.getMetadataProfiles();
            }
            KruizeOperator.getKruizeObjects(kruizeOperator);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
