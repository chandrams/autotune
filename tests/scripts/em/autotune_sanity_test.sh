#!/bin/bash
#
# Copyright (c) 2021, 2021 Red Hat, IBM Corporation and others.
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


function autotune_integration_test() {
	app="petclinic"
	instances=1

	test_name_=${FUNCNAME}
	input_json="${TEST_DIR_}/resources/em_input_json/${app}_em_input.json"
	echo  "************** input json = ${input_json}"

	# Deploy the application instances	
	deploy_app ${APP_REPO} ${app} ${instances}
	
	# Sleep for sometime for application pods to be up
	sleep 5

	# Post the input json to /createExperimentTrial API rest end point	
	post_experiment_json "${input_json}"

	sleep 10
	deployment_name=$(cat ${input_json} | jq '.deployments[0].deployment_name')

	echo "**** training deployment_name = ${deployment_name}"
	deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")

	get_config ${deployment_name} 
	validate_tunable_values ${test_name_}

	list_trial_status "${runid}"

	expected_exp_status="\"CREATED\""
	validate_exp_status "${exp_status}" "${expected_exp_status}" "${test_name_}"
	echo "Experiment status = ${exp_status}"

	expected_exp_status="\"WAITING_FOR_LOAD\""
	while [ ${exp_status} != ${expected_exp_status} ]
	do
		sleep 100
		list_trial_status "${runid}"
	done
	
	echo "Status of the deployment is ${exp_status}"

	echo "Starting load..."	
	start_load ${app} ${instances}

	# Metrics gathered
	expected_exp_status="\"COMPLETE\""
	while [ ${exp_status} != ${expected_exp_status} ]
	do


	expected_exp_status="\"COMPLETE\""
	while [ ${exp_status} != ${expected_exp_status} ]
	do
		sleep 100
		list_trial_status "${runid}"
	done
	echo "Status of the deployment is ${exp_status}"

	echo "Stopping load..."	
	stop_load ${app} ${instances}

	

	echo "----------------------------------------------------------------------------------------------"
}


