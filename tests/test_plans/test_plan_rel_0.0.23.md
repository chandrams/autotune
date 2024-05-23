# KRUIZE TEST PLAN RELEASE 0.0.23

- [INTRODUCTION](#introduction)
- [FEATURES TO BE TESTED](#features-to-be-tested)
- [BUG FIXES TO BE TESTED](#bug-fixes-to-be-tested)
- [TEST ENVIRONMENT](#test-environment)
- [TEST DELIVERABLES](#test-deliverables)
   - [New Test Cases Developed](#new-test-cases-developed)
   - [Regression Testing](#regresion-testing)
- [SCALABILITY TESTING](#scalability-testing)
- [RELEASE TESTING](#release-testing)
- [TEST METRICS](#test-metrics)
- [RISKS AND CONTINGENCIES](#risks-and-contingencies)
- [APPROVALS](#approvals)

-----

## INTRODUCTION

This document describes the test plan for Kruize remote monitoring release 0.0.23

----

## FEATURES TO BE TESTED

* Enhance Kruize logs & add metrics to capture error type of notifications

* Optimize updateRecommendations API for improved efficiency  

* Support multiple invocations of import metadata

* Enable logging of success and failures for APIs

* Develop tests for List Datasource metadata

------

## BUG FIXES TO BE TESTED

* Kruize pod fails if postgres doesn't comeup
* Analyze updateRecommendation failures in production

---

## TEST ENVIRONMENT

* Minikube Cluster
* Openshift Cluster 

---

## TEST DELIVERABLES

### New Test Cases Developed

| #   | ISSUE (NEW FEATURE)                                                                                                                  | TEST DESCRIPTION | TEST DELIVERABLES | RESULTS | COMMENTS |
| --- |--------------------------------------------------------------------------------------------------------------------------------------| ---------------- | ----------------- |  -----  | --- |
| 1   | [Enhance Kruize logs & add metrics to capture error type of notifications] (https://github.com/kruize/autotune/issues/)                                                                   | Tests added - [](https://github.com/kruize/autotune/pull/) |  | |
| 2   | [Support multiple invocations of import metadata] (https://github.com/kruize/autotune/issues/)                                                                   | Tests added - [](https://github.com/kruize/autotune/pull/) |  | |
| 3   | [Enable logging of success and failures for APIs] (https://github.com/kruize/autotune/issues/)                                                                   | Tests added - [](https://github.com/kruize/autotune/pull/) |  | |
| 4   | [Test list datasource metadata] (https://github.com/kruize/autotune/issues/)                                                                   | Tests added - [](https://github.com/kruize/autotune/pull/) |  | |
| 5   | [Optimize updateRecommendations API for improved efficiency] (https://github.com/kruize/autotune/issues/)                                                                   | Regression testing |  | |

### Regression Testing

| #   | ISSUE (BUG/NEW FEATURE)        |  TEST CASE | RESULTS | COMMENTS |
| --- |--------------------------------| ---------------- |---------| --- |
| 1   | Kruize remote monitoring tests | Functional test suite | | |
| 2   | Kruize fault tolerant tests | Functional test suite | | |
| 3   | Kruize stress tests | Functional test suite | | |
| 4   | Short Scalability test         | 5k exps / 15 days | | Time Taken: 

---

## SCALABILITY TESTING

Evaluate Kruize Scalability on OCP, with 5k experiments by uploading resource usage data for 15 days and update recommendations.
Changes do not have scalability implications. Short scalability test will be run as part of the release testing

Short Scalability run
- 5K exps / 15 days of results / 2 containers per exp
- Kruize replicas - 10
- OCP - Scalelab cluster

Kruize Release | Exps / Results / Recos | Execution time | Latency (Max/ Avg) in seconds | | | | Postgres DB size(MB) | Kruize Max CPU | Kruize Max Memory (GB)
-- |------------------|------------------|--------------|--------------|-------------|-----------|----------------| --  |  
 |     |                   | UpdateRecommendations | UpdateResults                 | LoadResultsByExpName | GeneratePlots|  |   |  
0.0.22_mvp | 5K / 72L / 3L | 3h 51 mins | 0.62 / 0.39 | 0.24 / 0.17 | 0.34 / 0.25  | 0.0008 / 0.0007 | 21756.32 | 7.12 | 33.64
0.0.23_mvp | 5K / 72L / 3L |  |  |  |  |  |  |

----
## RELEASE TESTING

As part of the release testing, following tests will be executed:
- [Kruize Remote monitoring Functional tests](/tests/scripts/remote_monitoring_tests/Remote_monitoring_tests.md)
- [Fault tolerant test](/tests/scripts/remote_monitoring_tests/fault_tolerant_tests.md)
- [Stress test](/tests/scripts/remote_monitoring_tests/README.md)
- [Scalability test (On openshift)](/tests/scripts/remote_monitoring_tests/scalability_test.md) - scalability test with 5000 exps / 15 days usage data
- [Kruize remote monitoring demo (On minikube)](https://github.com/kruize/kruize-demos/blob/main/monitoring/remote_monitoring_demo/README.md)


| #   | TEST SUITE | EXPECTED RESULTS                        | ACTUAL RESULTS                         | COMMENTS                                                                                                                                              |
| --- | ---------- |-----------------------------------------|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------| 
| 1   |  Kruize Remote monitoring Functional testsuite | TOTAL - 357, PASSED - 314 / FAILED - 43 | TOTAL - 357, PASSED - 314/ FAILED - 43 | No new regressions seen, existing issues - [559](https://github.com/kruize/autotune/issues/559), [610](https://github.com/kruize/autotune/issues/610) |
| 2   |  Kruize Local monitoring demo |                                         |                                        |                                                                                                                                                       |
| 3   |  Fault tolerant test | PASSED                                  | PASSED                                 |                                                                                                                                                       |
| 4   |  Stress test | PASSED                                  | PASSED                                 |                                                                                                                                                       |
| 5   |  Scalability test (short run)| PASSED                                  | PASSED                                 | Exps - 5000, Results - 72000, execution time -                                                                                           |
| 6   |  Kruize remote monitoring demo | PASSED                                  | PASSED                                 | Tested manually                                                                                                                                       |

---

## TEST METRICS

### Test Completion Criteria

* All must_fix defects identified for the release are fixed
* New features work as expected and tests have been added to validate these
* No new regressions in the functional tests
* All non-functional tests work as expected without major issues
* Documentation updates have been completed

----

## RISKS AND CONTINGENCIES

* None

----
## APPROVALS

Sign-off

----

