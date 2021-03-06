#!/bin/bash
#
# Copyright (c) 2020, 2021 Red Hat, IBM Corporation and others.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
##### Script for validating RM-HPO APIs #####

# Get the absolute path of current directory
CURRENT_DIR="$(dirname "$(realpath "$0")")"
SCRIPTS_DIR="${CURRENT_DIR}"
SEARCH_SPACE_JSON="${CURRENT_DIR}/../resources/searchspace_jsons/searchspace.json"

# Source the common functions scripts
. ${SCRIPTS_DIR}/hpo_api_constants.sh

# Tests to validate the RM-HPO APIs
function rm_hpo_api_tests() {
	start_time=$(get_date)
	FAILED_CASES=()
	TESTS_FAILED=0
	TESTS_PASSED=0
	TESTS=0
	((TOTAL_TEST_SUITES++))
	
	rm_hpo_api_tests=("rm_hpo_post_experiment")
	
	# check if the test case is supported
	if [ ! -z "${testcase}" ]; then
		check_test_case "rm_hpo_api"
	fi
	
	# create the result directory for given testsuite
	echo ""
	TEST_SUITE_DIR="${RESULTS}/rm_hpo_api_tests"
	mkdir -p ${TEST_SUITE_DIR}
	
	# If testcase is not specified run all tests	
	if [ -z "${testcase}" ]; then
		testtorun=("${rm_hpo_api_tests[@]}")
	else
		testtorun=${testcase}
	fi

	for test in "${testtorun[@]}"
	do
		TEST_DIR="${TEST_SUITE_DIR}/${test}"
		mkdir ${TEST_DIR}
		SETUP="${TEST_DIR}/setup.log"
		AUTOTUNE_LOG="${TEST_DIR}/${test}_autotune.log"
		LOG="${TEST_SUITE_DIR}/${test}.log"

		echo ""
		echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~" | tee -a ${LOG}
		echo "                    Running Test ${test}" | tee -a ${LOG}
		echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"| tee -a ${LOG}

		echo " " | tee -a ${LOG}
		echo "Test description: ${rm_hpo_api_test_description[$test]}" | tee -a ${LOG}
		echo " " | tee -a ${LOG}
		
		# Perform the test
		${test}
	done

	if [ "${TESTS_FAILED}" -ne "0" ]; then
		FAILED_TEST_SUITE+=(${FUNCNAME})
	fi
	
	end_time=$(get_date)
	elapsed_time=$(time_diff "${start_time}" "${end_time}")

	# Remove the duplicates 
	FAILED_CASES=( $(printf '%s\n' "${FAILED_CASES[@]}" | uniq ) )

	# print the testsuite summary
	testsuitesummary ${FUNCNAME} ${elapsed_time} ${FAILED_CASES} 
}

# Post a JSON object to HPO(Hyper Parameter Optimization) module
# input: JSON object
# output: Create the Curl command with given JSON and get the result
function post_experiment_json() {
	json_array_=$1
	
	content='curl -H "Content-Type: application/json"'
	post_cmd=$(curl -H "Content-Type: application/json" -d "${json_array_}"  http://localhost:8085/experiment_trials -w '\n%{http_code}' 2>&1)
	# Example curl command: curl -H "Content-Type: application/json" -d {"id" : "a123", "url" : "http://localhost:8080/searchSpace", "operation" : "EXP_TRIAL_GENERATE_NEW"}  http://localhost:8085/experiment_trials -w n%{http_code}
	post_experiment_cmd="${content} -d ${json_array_}  http://localhost:8085/experiment_trials -w '\n%{http_code}'"
	
	echo "" | tee -a ${LOG_} ${LOG}
	echo "Command used to post the experiment= ${post_experiment_cmd}" | tee -a ${LOG_} ${LOG}
	echo "" | tee -a ${LOG_} ${LOG}
	
	echo "${post_cmd}" >> ${LOG_} ${LOG}
	
	http_code=$(tail -n1 <<< "${post_cmd}")
	response=$(echo -e "${post_cmd}" | tail -2 | head -1)
	
	echo "Response is ${response}" >> ${LOG_} ${LOG}
}

function check_server_status() {
	searchspace_log_msg="Starting server at http://localhost:8080"
	service_log_msg="Starting server at http://localhost:8085"
	
	echo ""
	if grep -q "${searchspace_log_msg}" "${TESTS_}/searchspace.log" ; then
		echo "Searchspace service started successfully..." | tee -a ${LOG_} ${LOG}
	else
		echo "Error Starting the searchspace service..." | tee -a ${LOG_} ${LOG}
		echo "See ${TESTS_}/searchspace.log for more details" | tee -a ${LOG_} ${LOG}
		exit 0
	fi
	
	if grep -q "${service_log_msg}" "${TESTS_}/service.log" ; then
		echo "HPO REST API service started successfully..." | tee -a ${LOG_} ${LOG}
	else
		echo "Error Starting the HPO REST API service..." | tee -a ${LOG_} ${LOG}
		echo "See ${TESTS_}/service.log for more details" | tee -a ${LOG_} ${LOG}
		exit 0
	fi
}

# Do a post with JSON array having invalid fields
# input: Test name
# output: get the JSON, post it to HPO and compare the result
function invalid_post(){
	__test_name__=$1
	for post_test in "${invalid_post_tests[@]}"
	do
		TESTS_="${TEST_DIR}/${post_test}"
		mkdir -p ${TESTS_}
		LOG_="${TEST_DIR}/${post_test}.log"
		
		echo "************************************* ${post_test} Test ****************************************" | tee -a ${LOG_} ${LOG}
		echo "" | tee -a ${LOG_} ${LOG}

		case "${post_test}" in
			invalid-searchspace)
				exp="valid-experiment"
				sed 's/"sla_class": "response_time"/"sla_class": "xyz"/g' ${SEARCH_SPACE_JSON} > ${TESTS_}/invalid_searchspace.json
				echo "Searchspace JSON" | tee -a ${LOG_} ${LOG}
				cat ${TESTS_}/invalid_searchspace.json | tee -a ${LOG_} ${LOG}
				echo "" | tee -a ${LOG_} ${LOG}
				# Start the HPO servers
				${SCRIPTS_DIR}/start_hpo_servers.sh -p ${TESTS_} -j ${TESTS_}/invalid_searchspace.json | tee -a ${LOG_} ${LOG}
				;;
			*)
				exp="${post_test}"	
				# Start the HPO servers
				${SCRIPTS_DIR}/start_hpo_servers.sh -p ${TESTS_} -j ${SEARCH_SPACE_JSON} | tee -a ${LOG_} ${LOG}
		esac
		
		# Sleep for few seconds to reduce the ambiguity
		sleep 2
		
		# Check if the servers have started
		check_server_status
		
		# Get the id from search space JSON
		current_id=$(cat ${SEARCH_SPACE_JSON} | jq .[].id | tr -d '""')
		
		create_post_exp_json_array ${current_id}
		post_experiment_json "${rm_hpo_post_experiment_json[$exp]}"
	
		if [[ "${post_test}" == valid* ]]; then
			expected_result_="200"
			expected_behaviour="RESPONSE_CODE = 200 OK"
		else
			expected_result_="^4[0-9][0-9]"
			expected_behaviour="RESPONSE_CODE = 4XX BAD REQUEST"
		fi
		
		actual_result="${http_code}"
		if [[ "${http_code}" -eq "000" ]]; then
			expected_log_msg="${rm_hpo_error_messages[${exp}]}"
			if grep -q "${expected_log_msg}" "${TESTS_}/service.log" ; then
				failed=0 
			else
				failed=1
			fi
			((TOTAL_TESTS++))
			((TESTS++))
			error_message ${failed}
		else
			compare_result ${__test_name__} ${expected_result_} "${expected_behaviour}"
		fi
		echo ""
		
		# Stop the HPO servers
		${SCRIPTS_DIR}/start_hpo_servers.sh -t | tee -a ${LOG_} ${LOG}
		
		# Sleep for few seconds to reduce the ambiguity
		sleep 2
		
		echo "" | tee -a ${LOG_} ${LOG}
	done

	echo "*********************************************************************************************************" | tee -a ${LOG_} ${LOG}
}

# Do a post on experiment_trials for the same id again with "operation: EXP_TRIAL_GENERATE_NEW" and check if experiments have started from the begining
function post_duplicate_experiments() {
	flag=0
	create_post_exp_json_array ${current_id}
	post_experiment_json "${rm_hpo_post_experiment_json[$exp]}"
	
	if [ "${http_code}" == "200" ]; then
		failed=0
	
		# Post the json with same Id having "operation: EXP_TRIAL_GENERATE_NEW"
		echo "Post the json with same Id having operation: EXP_TRIAL_GENERATE_NEW" | tee -a ${LOG_} ${LOG}
		
		# Sleep for few seconds to reduce the ambiguity
		sleep 2
		
		post_experiment_json "${rm_hpo_post_experiment_json[$exp]}"
		
		actual_result="${http_code}"
		expected_result_="^4[0-9][0-9]"
		expected_behaviour="RESPONSE_CODE = 4XX BAD REQUEST"
	
		compare_result ${__test_name__} ${expected_result_} "${expected_behaviour}"
	else
		failed=1
		expected_result_="200"
		expected_behaviour="RESPONSE_CODE = 200 OK"
		((TOTAL_TESTS++))
		((TESTS++))
		error_message ${failed}
	fi
}

# Post the experiment result to HPO module
# input: Experiment result
# output: Create the Curl command with given JSON and get the result
function post_exp_trial_result() {
	trial_num=$1
	exp_result='{"id" : "'${current_id}'", "trial_number": '${trial_num}', "trial_result": "success", "result_value_type": "double", "result_value": 98.78, "operation" : "EXP_TRIAL_RESULT"}'
	_post_=$(curl -H "Content-Type: application/json" -d "${exp_result}" http://localhost:8085/experiment_trials -w '\n%{http_code}' 2>&1)
	post_exp_result_cmd='curl -H "Content-Type: application/json" -d '${exp_result}' http://localhost:8085/experiment_trials -w '\n%{http_code}''

	echo "" | tee -a ${LOG_} ${LOG}
	echo "Command used to post the experiment result= ${post_exp_result_cmd}" | tee -a ${LOG_} ${LOG}
	echo "" | tee -a ${LOG_} ${LOG}

	echo "${_post_}" >> ${LOG_} ${LOG}

	http_code=$(tail -n1 <<< "${_post_}")
	response=$(echo -e "${_post_}" | tail -2 | head -1)
	
	echo "Response is ${response}" >> ${LOG_} ${LOG}
}

# Do a post on experiment_trials for the same id again with "operation: EXP_TRIAL_GENERATE_SUBSEQUENT" and check if same experiment continues
function operation_generate_subsequent() {
	create_post_exp_json_array ${current_id}
	post_experiment_json "${rm_hpo_post_experiment_json[$exp]}"
	
	# Sleep for few seconds to reduce the ambiguity
	sleep 5
	
	trial_num="${response}"
	post_exp_trial_result ${trial_num}
	
	# Sleep for few seconds to reduce the ambiguity
	sleep 5
	
	# Post the json with same Id having "operation: EXP_TRIAL_GENERATE_SUBSEQUENT"
	echo "Post the json with same Id having operation: EXP_TRIAL_GENERATE_SUBSEQUENT" | tee -a ${LOG_} ${LOG}
	exp="generate-subsequent"
	post_experiment_json "${rm_hpo_post_experiment_json[$exp]}"
	
	actual_result="${response}"
	expected_result_=$(($trial_num+1))
	expected_behaviour="trial_number = '${expected_result_}'"
	
	compare_result ${__test_name__} ${expected_result_} "${expected_behaviour}"
}

# Other RM-HPO post experiment tests
function other_post_experiment_tests() {
	__test_name__=$1
	exp="valid-experiment"
	
	for operation in "${other_post_experiment_tests[@]}"
	do
		TESTS_="${TEST_DIR}/${operation}"
		mkdir -p ${TESTS_}
		LOG_="${TEST_DIR}/${operation}.log"
		echo "************************************* ${operation} Test ****************************************" | tee -a ${LOG_} ${LOG}
		
		# Start the HPO servers
		${SCRIPTS_DIR}/start_hpo_servers.sh -p ${TESTS_} -j ${SEARCH_SPACE_JSON} | tee -a ${LOG_} ${LOG}
		
		# Sleep for few seconds to reduce the ambiguity
		sleep 2
		
		# Check if the servers have started
		check_server_status
				
		# Get the id from search space JSON
		current_id=$(cat ${SEARCH_SPACE_JSON} | jq .[].id | tr -d '""')
		
		operation=$(echo ${operation//-/_})
		${operation}
		echo ""
		
		# Stop the HPO servers
		${SCRIPTS_DIR}/start_hpo_servers.sh -t | tee -a ${LOG_} ${LOG}
		
		# Sleep for few seconds to reduce the ambiguity
		sleep 2
	done
		
	echo "*********************************************************************************************************" | tee -a ${LOG_} ${LOG}	
}

# Tests for RM-HPO POST experiment
function rm_hpo_post_experiment() {
	invalid_post ${FUNCNAME}
	other_post_experiment_tests ${FUNCNAME}
}
