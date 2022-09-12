#!/bin/bash
#
# Copyright (c) 2022, 2022 Red Hat, IBM Corporation and others.
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
##### Deployment validation tests for experiment manager #####


app="petclinic"

# Basic Deployment validation test for Experiment manager (EM). This test deploys an application and creates an experiment with the provided config
# that has an incorrect namespace
function validate_incorrect_ns() {
	instances=1
	app_namespace="incorrect-ns"

	test_name_=${FUNCNAME}
	input_json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	echo  "input json = ${input_json}"

	# Deploy application 	
	deploy_app ${APP_REPO} ${app} ${instances}
	
	# Sleep for sometime for application pods to be up
	sleep 5

	# Make a copy of the input json
	json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	input_json="${TEST_DIR}/${app}_em_input.json"
	echo  "input json = ${input_json}"
	Cp ${json} ${input_json}

	# Update the json with specified field          
	f="default"
       	replace="${app_namespace}"
	echo "find = $f replace = $replace"
	sed -i "s|${f}|${replace}|g" ${input_json}


	# Post the input json to /createExperimentTrial API rest end point	
	post_experiment_json "${input_json}"

	sleep 10
	deployment_name=$(cat ${input_json} | jq '.deployments[0].deployment_name')

	deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")

	get_config ${deployment_name} 
	validate_tunable_values ${test_name_} ${input_json} ${deployment_name}

	list_trial_status "${runid}"

	expected_exp_status="\"WAITING_FOR_LOAD\""
	validate_exp_status "${exp_status}" "${expected_exp_status}" "${test_name_}"
	echo "Experiment status = ${exp_status}"
	echo "----------------------------------------------------------------------------------------------"
	app_cleanup ${app}
}

function validate_single_deployment_nondefault_ns() {
	instances=1
	app_namespace="petclinic"

	test_name_=${FUNCNAME}
	
	# Deploy application 	
	deploy_app ${APP_REPO} ${app} ${instances} ${app_namespace}
	
	# Sleep for sometime for application pods to be up
	sleep 5

	# Make a copy of the input json
	json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	input_json="${TEST_DIR}/${app}_em_input.json"
	echo  "************** input json = ${input_json}"
	cp ${json} ${input_json}

	# Update the json with specified field          
	f="default"
       	replace="${app_namespace}"
	echo "find = $f replace = $replace"
	sed -i "s|${f}|${replace}|g" ${input_json}

	# Post the input json to /createExperimentTrial API rest end point	
	post_experiment_json "${input_json}"

	sleep 10
	deployment_name=$(cat ${input_json} | jq '.deployments[0].deployment_name')

	deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")

	get_config ${deployment_name} ${app_namespace} 
	validate_tunable_values ${test_name_} ${input_json} ${deployment_name}

	list_trial_status "${runid}"

	expected_exp_status="\"WAITING_FOR_LOAD\""
	validate_exp_status "${exp_status}" "${expected_exp_status}" "${test_name_}"
	echo "Experiment status = ${exp_status}"
	echo "----------------------------------------------------------------------------------------------"
	app_cleanup ${app}
}

function validate_single_deployment_same_config() {
	instances=1

	configs=2
	test_name_=${FUNCNAME}

	# Deploy application instances	
	deploy_app ${APP_REPO} ${app} ${instances}
	
	# Sleep for sometime for application pods to be up
	sleep 5


	for (( i=0; i<${configs}; i++ ))
	do
		json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
		input_json="${TEST_DIR}/${app}-${i}_em_input.json"
		echo  "input json = ${input_json}"
		cp ${json} ${input_json}

		# Post the input json to /createExperimentTrial API rest end point	
		post_experiment_json ${input_json}

		sleep 10
		deployment_name=$(cat ${input_json} | jq '.resource.deployment_name')

		deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")

		list_trial_status "${runid}"

		echo "Experiment status = ${exp_status}"
		if [ "${i}" == 0 ]; then

			expected_exp_status="\"WAITING_FOR_LOAD\""
			validate_exp_status "${exp_status}" "${expected_exp_status}" "${test_name_}"
			get_config ${deployment_name}
			validate_tunable_values ${test_name_} ${input_json} ${deployment_name}
		else
			expected_exp_status="\"WAIT\""
			validate_exp_status "${exp_status}" "${expected_exp_status}" "${test_name_}"
			# Check if the app has been deployed
			list_trial_status "${runid}"

			expected_exp_status="\"WAITING_FOR_LOAD\""

		        while [ ${exp_status} != ${expected_exp_status} ]
		        do
		                sleep 100
                		list_trial_status "${runid}"
		        done

		        echo "Status of the deployment is ${exp_status}"

			get_config ${deployment_name}
			validate_tunable_values ${test_name_} ${input_json} ${deployment_name}
		fi

		#Add validation to check if pod has been restarted if restart is 'yes' 

		echo "----------------------------------------------------------------------------------------------"
	done
	app_cleanup ${app}
}

function invalid_input_json() {
	input_json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	for testcase in ${invalid_input_json_testcases[@]}
	do
		LOG_DIR="${LOG}/${testcase}.log"
		json_dir="${TEST_DIR}/${testcase}"
		mkdir -p ${json_dir}
		
		typeset -n find="invalid_input_json_find[${testcase}]"
		typeset -n tests="${testcase}_tests"
		
		for test in "${tests[@]}"
		do
			typeset -n replace="${testcase}_replace[${test}]"

			echo "*********** replace = $replace"
			
			generate_input_json ${test}
			post_invalid_experiment_json ${json_dir}/${test}.json
			validate_post_result ${FUNCNAME}
		done
	done
}


function validate_no_deployment_exp() {

	test_name_=${FUNCNAME}
	input_json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	echo  "EM input json = ${input_json}"

	sleep 5

	# Post the input json to /createExperimentTrial API rest end point	
	post_experiment_json "${input_json}"

	#validate_single_deployment

	echo "----------------------------------------------------------------------------------------------"
}

# Basic Deployment validation test for Experiment manager (EM). This test deploys an application and creates an experiment with the provided config
# that has an incorrect namespace


function check_duplication() {
	post_experiment_json ${input_json}.json
	post_experiment_json ${input_json}.json
	validate_post_result
}

function same_trial_diff_metrics() {
	LOG_DIR="${LOG}/${FUNCNAME}.log"
	json_dir="${input_json_dir}/${FUNCNAME}"
	mkdir -p ${json_dir}
	
	typeset -n find="same_trial_find[${FUNCNAME}]"
	typeset -n tests="${FUNCNAME}_tests"
	
	for test in "${tests[@]}"
	do
		typeset -n replace="${FUNCNAME}_replace[${test}]"
			
		generate_input_json ${test}
		post_experiment_json ${json_dir}/${test}.json
		validate_post_result ${FUNCNAME}
	done
}
