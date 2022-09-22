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

function form_em_curl_cmd {
        API=$1
	cluster_type=$2

	echo "In form_em_curl_cmd = $cluster_type"
        # Form the curl command based on the cluster type
        case $cluster_type in
           openshift) ;;
           minikube)
                NAMESPACE="monitoring"
                echo "NAMESPACE = ${NAMESPACE}"
                AUTOTUNE_PORT=$(kubectl -n ${NAMESPACE} get svc autotune --no-headers -o=custom-columns=PORT:.spec.ports[*].nodePort)
                SERVER_IP=$(minikube ip)
                AUTOTUNE_URL="http://${SERVER_IP}"
                ;;
           docker) ;;
           *);;
        esac

        if [ $cluster_type == "openshift" ]; then
                em_curl_cmd="curl -s -H 'Content-Type: application/json' ${AUTOTUNE_URL}/${API}"
        else
                em_curl_cmd="curl -s -H 'Content-Type: application/json' ${AUTOTUNE_URL}:${AUTOTUNE_PORT}/${API}"
        fi
}

function post_experiment() {
        exp_json=$1
	cluster_type=$2

        echo "Forming the curl command to post the experiment..."
        form_em_curl_cmd "createExperimentTrial" "${cluster_type}"

        echo "em_curl_cmd = ${em_curl_cmd}"
        em_curl_cmd="${em_curl_cmd} -d @${exp_json}"

        echo "em_curl_cmd = ${em_curl_cmd}"

        exp_status_json=$(${em_curl_cmd})
        echo "Post experiment status = $exp_status_json"

        echo ""
        echo "Command used to post the experiment result= ${em_curl_cmd}"
        echo ""

}

function start_experiment() {
	index=$1
	N_TRIALS=$2
	EXP_RES_DIR=$3
	TEST_DIR_=$4
	cluster_type=$5

	echo "index=$index N_TRIALS=$N_TRIALS EXP_RES_DIR=$EXP_RES_DIR"

	em_json="${TEST_DIR_}/resources/em_input_json/GeneralPerfExp.json"
	em_input_json="${EXP_RES_DIR}/GeneralPerfExp-exp${index}.json"

	echo "em_input json = ${em_input_json}"
	cp "${em_json}" "${em_input_json}"

	deployment_name="tfb-qrh-sample-0"
	new_deployment_name="tfb-qrh-sample-${index}"

	experiment_name=$(cat ${em_json} | jq '.[0].experiment_name')

	echo "deployment_name = ${deployment_name}"
	echo "experiment_name = ${experiment_name}"

	experiment_name=$(echo ${experiment_name} | sed -e "s/\"//g")

	# Update the experiment name and deployment name for each experiment and post
	sed -i 's/"'${experiment_name}'"/"'${experiment_name}'-exp'${index}'"/g' ${em_input_json}
	sed -i 's/"'${deployment_name}'"/"'${new_deployment_name}'"/g' ${em_input_json}

	# Post the input json to /createExperimentTrial API
	post_experiment "${em_input_json}" "${cluster_type}"

	sleep 5

	for (( j=1; j<=N_TRIALS; j++ ))
	do
		input_json="${EXP_RES_DIR}/GeneralPerfExp-exp${index}-trial${j}.json"
		cp "${em_input_json}" "${input_json}"

		# Update the config and post
		mem=$(cat ${input_json} | jq '.[].trials."0".config.requests.memory.amount')
		echo "mem = $mem"

		y=$(echo $mem | sed -e 's/\"//g')
		mem=$(($y+$j))
		echo "mem = $mem"
		sed -i 's/"amount": "180"/"amount": "'${mem}'"/g' ${input_json}
		sed -i 's/"0"\:/"'${j}'"\:/g' ${input_json}

		# Post the input json to /createExperimentTrial API
		post_experiment "${input_json}" "${cluster_type}"

		deployment_name=$(cat ${input_json} | jq '.[0].resource.deployment_name')
		deployment_name=$(echo ${deployment_name} | sed -e "s/\"//g")
		echo "deployment_name = $deployment_name"

		sleep 5

		kubectl get deployment ${new_deployment_name} -o json -n default > "${EXP_RES_DIR}/deployconfig-exp${index}-trial${j}.json"
	done

}

export -f start_experiment form_em_curl_cmd post_experiment

function validate_em_multiple_exps() {
	app="tfb-qrh"
	instances=3

	echo "cluster_type = $cluster_type namespace = $NAMESPACE"
	test_name_=${FUNCNAME}

	app_cleanup ${app}
	# Deploy the application with the specified number of instances	
	#deploy_app ${APP_REPO} ${app} ${instances}
	
	# Sleep for sometime for application pods to be up
	sleep 5

	N_EXPS=300
	N_TRIALS=5
	namespace="default"

	## Start multiple experiments
	EXP_RES_DIR="${TEST_DIR}/exp_logs"
	mkdir -p ${EXP_RES_DIR}

	for (( i=1 ; i<=${N_EXPS} ; i++ ))
	do
		bash -c "start_experiment ${i} ${N_TRIALS} ${EXP_RES_DIR} ${TEST_DIR_} ${cluster_type} > ${EXP_RES_DIR}/start_exp_${i}.log 2>&1" &
		sleep 2
		pid[${i}]=$!
		echo "pid = ${pid[${i}]}"
	done

	echo "Wait for all background processes..."
	for (( i=1 ; i<=${N_EXPS} ; i++ ))
	do
		wait ${pid[${i}]}

		# Remove experiments
	done

	echo "Fetching list trial status summary..."
	list_trial_status_summary

	# Cleanup the deployed application
	app_cleanup ${app}
	echo "RESULTS_DIR = ${TEST_DIR}"
	echo "----------------------------------------------------------------------------------------------"
}

