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
##### EM A/B validation tests for experiment manager #####


# EM A/B validation test for Experiment manager (EM). This test deploys an application and creates an experiment with the provided config
# and validates the rolling update config against the provided config. It also validates which version of the application performs better

function validate_em_multiple_trials() {
	app="tfb-qrh"
	instances=1
	MAX_LOOP=3

	test_name_=${FUNCNAME}
	em_json="${TEST_DIR_}/resources/em_input_json/GeneralPerfExp.json"

	deployment_name=$(cat ${em_json} | jq '.[0].resource.deployment_name')
	experiment_name=$(cat ${em_json} | jq '.[0].experiment_name')

	echo "deployment_name = ${deployment_name}"
	echo "experiment_name = ${experiment_name}"
	deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")


	# Deploy the application with the specified number of instances	
	deploy_app ${APP_REPO} ${app} ${instances}
	
	# Sleep for sometime for application pods to be up
	sleep 5

	N_TRIALS=5
	namespace="default"
	for (( i=0; i<N_TRIALS; i++ ))
	do
		input_json="${TEST_DIR}/GeneralPerfExp-${i}.json"
		echo "input json = ${input_json}"
		cp "${em_json}" "${input_json}"

		# Update the config and post
		mem=$(cat ${input_json} | jq '.[].trials."0".config.requests.memory.amount')
		echo "mem = $mem"

		y=$(echo $mem | sed -e 's/\"//g')
		mem=$(($y + $i))
		echo "mem = $mem"
		sed -i 's/"amount": "180"/"amount": "'${mem}'"/g' ${input_json}
		sed -i 's/"0"\:/"'${i}'"\:/g' ${input_json}

		# Post the input json to /createExperimentTrial API
		post_experiment_json "${input_json}"

		kubectl get deployment ${deployment_name} -o json -n ${namespace} > "${TEST_DIR}/${deployment_name}-${i}.json"

		# Obtain the status of the experiment
	#	trial_num="${i}"
	#	list_trial_status "${experiment_name}" "${trial_num}"
		expected_exp_status="\"WAITING_FOR_LOAD\""

	#	timeout 120s bash -c 'while [ ${exp_status} != ${expected_exp_status} ]; do sleep 5;  list_trial_status "${experiment_name}";  done'

		#counter=1
		#while [ "${exp_status}" != "${expected_exp_status}" ]
		#do
		#	sleep 1
		#	list_trial_status "${experiment_name}" "${trial_num}"
		#	counter=$((counter+1))

		#	if [ ${counter} == "2" ]; then
		#		echo "Status of the experiment is not as expected (WAITING_FOR_LOAD)!"
		#		break
		#	fi
		#done

		# Start the load
		#start_load "${app}" "${instances}" "${MAX_LOOP}"

		# Check if the metrics has been gathered
		#expected_exp_status="\"COMPLETED\""
		#counter=1
		#while [ ${exp_status} != ${expected_exp_status} ]
       		#do
		#	sleep 2
		#	list_trial_status "${experiment_name}" "${trial_num}"
		#	$(( counter++ ))
	
		#	if [ ${counter} == "1" ]; then
		#		echo "Status of the experiment is not as expected (COMPLETED)!"
		#		break
		#	fi
		#done

		#list_trial_status "${experiment_name}" "${trial_num}"
		#echo "Status of the deployment is ${exp_status}"

		# Stop the load
		#echo "Stopping load..."
		#stop_load "${app}" 

		# Validate the metrics  
		# validate_exp_trial_result "${experiment_name}" "${trial_num}"
	done

	list_trial_status_summary

	# Cleanup the deployed application
#	app_cleanup ${app}
	echo "----------------------------------------------------------------------------------------------"
}

