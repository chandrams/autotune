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
package com.autotune;

import com.autotune.analyzer.utils.AnalyzerConstants;
import com.autotune.database.helper.DBConstants;
import com.autotune.operator.KruizeDeploymentInfo;
import com.autotune.operator.InitializeDeployment;
import com.autotune.utils.KruizeConstants;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Startup utilities retained from the legacy Jetty entry point.
 *
 * The Jetty server, servlet wiring, {@code InitiateListener}, {@code KruizeCORSFilter},
 * and Prometheus {@code MetricsServlet} have been removed. Quarkus now owns:
 *   • HTTP serving (RESTEasy Reactive resources under {@code com.autotune.*.services.*})
 *   • CORS ({@code quarkus.http.cors.*} in {@code application.properties})
 *   • Metrics ({@code quarkus.micrometer.export.prometheus.path=/metrics})
 *   • Startup ({@link com.autotune.operator.KruizeStartupBean} — {@code @Observes StartupEvent})
 *   • Configuration ({@link com.autotune.operator.QuarkusStartupBean} — {@code @ConfigMapping})
 */
public class Autotune {
    private static final Logger LOGGER = LoggerFactory.getLogger(Autotune.class);

    private Autotune() {}

    /**
     * Reads and executes all {@code .sql} files found at {@code target/migrations/<ddlPathOrFile>}.
     *
     * NOTE: This method is retained for compatibility during the Flyway transition period.
     * Once all environments are baselined with Flyway (ADR 3.4), this method can be removed.
     */
    public static void executeDDLs(String ddlPathOrFile) throws Exception {
        SessionFactory factory = io.quarkus.arc.Arc.container().instance(SessionFactory.class).get();
        Session session = null;

        Path basePath = Paths.get(AnalyzerConstants.TARGET,
                AnalyzerConstants.MIGRATIONS, ddlPathOrFile);

        try {
            List<Path> sqlFiles = new ArrayList<>();
            if (Files.notExists(basePath)) {
                throw new IllegalArgumentException("DDL path does not exist: " + basePath);
            }

            if (Files.isRegularFile(basePath)) {
                sqlFiles.add(basePath);
            } else if (Files.isDirectory(basePath)) {
                try (Stream<Path> walk = Files.walk(basePath)) {
                    sqlFiles = walk
                            .filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".sql"))
                            .collect(Collectors.toList());
                }
                sqlFiles.sort((p1, p2) -> {
                    Integer v1 = extractVersion(p1.getFileName().toString());
                    Integer v2 = extractVersion(p2.getFileName().toString());
                    if (v1 != null && v2 != null) return Integer.compare(v1, v2);
                    else if (v1 != null) return -1;
                    else if (v2 != null) return 1;
                    else return p1.getFileName().toString().compareTo(p2.getFileName().toString());
                });
            } else {
                throw new IllegalArgumentException("Provided path is not a file or directory: " + basePath);
            }

            if (sqlFiles.isEmpty()) {
                LOGGER.info("No SQL files found at path: {}", basePath);
                return;
            }

            session = factory.openSession();

            for (Path sqlFilePath : sqlFiles) {
                LOGGER.debug("Applying SQL file: {}", sqlFilePath.toString());
                String sqlStatement = Files.readString(sqlFilePath, StandardCharsets.UTF_8);
                Transaction transaction = session.beginTransaction();
                String trimmed = sqlStatement.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")
                        || trimmed.startsWith("#") || trimmed.startsWith("/*")) {
                    continue;
                }
                try {
                    session.createNativeQuery(trimmed).executeUpdate();
                } catch (Exception e) {
                    String msg = Optional.ofNullable(e.getMessage()).orElse("");
                    if (msg.contains(DBConstants.DB_MESSAGES.ADD_CONSTRAINT)) {
                        LOGGER.warn("sql: {} — duplicate constraint, skipping", trimmed);
                    } else if (msg.contains(DBConstants.DB_MESSAGES.ADD_COLUMN)) {
                        LOGGER.warn("sql: {} — duplicate column, skipping", trimmed);
                    } else {
                        LOGGER.error("sql: {} failed: {}", trimmed, msg);
                    }
                    try { transaction.commit(); } catch (Exception ignore) {}
                    transaction = session.beginTransaction();
                }
                try { transaction.commit(); } catch (Exception e) {
                    LOGGER.error("Failed to commit transaction for {}: {}", sqlFilePath, e.getMessage());
                }
            }
            LOGGER.info(DBConstants.DB_MESSAGES.DB_CREATION_SUCCESS);
        } catch (Exception e) {
            LOGGER.error("Exception while executing DDLs: {}", e.getMessage());
            throw new Exception(e);
        } finally {
            if (session != null) session.close();
        }
        LOGGER.info(DBConstants.DB_MESSAGES.DB_LIVELINESS_PROBE_SUCCESS);
    }

    /** Extracts the leading numeric version from a Flyway-style filename (e.g. {@code v001__...sql} → 1). */
    private static Integer extractVersion(String filename) {
        if (filename == null) return null;
        Pattern p = Pattern.compile("^v0*([0-9]+)__.*", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(filename);
        if (m.matches()) {
            try { return Integer.valueOf(m.group(1)); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
