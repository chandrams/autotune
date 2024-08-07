#!/bin/bash
#
# Copyright (c) 2023, 2023 IBM Corporation, RedHat and others.
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
### Script to run scale test with Kruize in local monitoring mode ##
#

CURRENT_DIR="$(dirname "$(realpath "$0")")"
KRUIZE_REPO="${CURRENT_DIR}/../../../../"


# Source the common functions scripts
. ${CURRENT_DIR}/../../common/common_functions.sh

RESULTS_DIR=kruize_scale_test_results
APP_NAME=kruize
CLUSTER_TYPE=openshift
DEPLOYMENT_NAME=kruize
CONTAINER_NAME=kruize
NAMESPACE=openshift-tuning
num_clusters=1
num_namespaces=1
num_workloads=1
num_exps=5000
num_days_of_res=15
num_clients=20
minutes_jump=15
interval_hours=1
initial_start_date="2023-01-10T00:00:00.000Z"
query_db_interval=10

kruize_setup=true
restore_db=false
db_backup_file="./db_backup.sql"

replicas=1

target="crc"
KRUIZE_IMAGE="quay.io/kruize/autotune:mvp_demo"
hours=1
total_results_count=0

function usage() {
	echo
	echo "Usage: [-i Kruize image] [-u No. of experiments (default - 5000)] [-d No. of days of results (default - 15)] [-n No. of clients (default - 20)] [-m results duration interval in mins, (default - 15)] [-t interval hours (default - 6)] [-s Initial start date (default - 2023-01-10T00:00:00.000Z)] [-q query db interval in mins, (default - 10)] [-r <resultsdir path>] [-l restore DB (default - false)] [-f DB file path to restore (default - ./db_backup.sql)] [-b kruize setup (default - true)]"
	exit -1
}

function get_kruize_pod_log() {
	log_dir=$1

	# Fetch the kruize pod log

	echo ""
	echo "Fetch the kruize pod logs..."

	pod_list=$(kubectl get pods -n ${NAMESPACE} -l app=kruize --output=jsonpath='{.items[*].metadata.name}')
	echo $pod_list
	mkdir -p "${log_dir}/pod_logs"
	for pod in $pod_list; do
		kubectl logs -n ${NAMESPACE} $pod > "${log_dir}/pod_logs/$pod.log" 2>&1 &
	done
}

function get_kruize_service_log() {
        log=$1

        # Fetch the kruize service log

        echo ""
        echo "Fetch the kruize service logs and store in ${log}..."
        kruize_pod="svc/kruize"
        kubectl logs -f ${kruize_pod} -n ${NAMESPACE} > ${log} 2>&1 &
}

#
# "local" flag is turned off by default for now. This needs to be set to true.
#
function kruize_local_thanos_patch() {
        CRC_DIR="./manifests/crc/default-db-included-installation"
        KRUIZE_CRC_DEPLOY_MANIFEST_OPENSHIFT="${CRC_DIR}/openshift/kruize-crc-openshift.yaml"
        KRUIZE_CRC_DEPLOY_MANIFEST_MINIKUBE="${CRC_DIR}/minikube/kruize-crc-minikube.yaml"

	# Checkout mvp_demo to get the latest mvp_demo release version
        git checkout mvp_demo > /dev/null 2>/dev/null

        if [ ${CLUSTER_TYPE} == "minikube" ]; then
        	sed -i 's/"local": "false"/"local": "true"/' ${KRUIZE_CRC_DEPLOY_MANIFEST_MINIKUBE}
	elif [ ${CLUSTER_TYPE} == "openshift" ]; then
        	sed -i 's/"local": "false"/"local": "true"/' ${KRUIZE_CRC_DEPLOY_MANIFEST_OPENSHIFT}
		sed -i 's/"name": "prometheus-1"/"name": "thanos"/' ${KRUIZE_CRC_DEPLOY_MANIFEST_OPENSHIFT}
                sed -i 's/"url": "https:\/\/prometheus-k8s.openshift-monitoring.svc.cluster.local:9091"/"url": "http:\/\/thanos-query-frontend-thanos-bench.apps.kruize-scalelab.h0b5.p1.openshiftapps.com"/' ${KRUIZE_CRC_DEPLOY_MANIFEST_OPENSHIFT}
        fi
}


while getopts r:i:x:w:u:d:t:n:m:s:l:f:b:e:q:h gopts
do
	case ${gopts} in
	r)
		RESULTS_DIR="${OPTARG}"		
		;;
	i)
		KRUIZE_IMAGE="${OPTARG}"		
		;;
	x)
		num_clusters="${OPTARG}"		
		;;
	u)
		num_namespaces="${OPTARG}"		
		;;
	w)
		num_workloads="${OPTARG}"		
		;;
	d)
		num_days_of_res="${OPTARG}"		
		;;
	n)
		num_clients="${OPTARG}"		
		;;
	m)
		minutes_jump="${OPTARG}"		
		;;
	s)
		initial_start_date="${OPTARG}"		
		;;
	t)
		interval_hours="${OPTARG}"		
		;;
	q)
		query_db_interval="${OPTARG}"		
		;;
	l)
		restore_db="${OPTARG}"		
		;;
	f)
		db_backup_file="${OPTARG}"		
		;;
	b)
		kruize_setup="${OPTARG}"
		;;
	e)
		total_results_count="${OPTARG}"
		;;
	h)
		usage
		;;
	esac
done

start_time=$(get_date)
LOG_DIR="${RESULTS_DIR}/local-monitoring-scale-test-$(date +%Y%m%d%H%M)"
mkdir -p ${LOG_DIR}

LOG="${LOG_DIR}/local-monitoring-scale-test.log"

prometheus_pod_running=$(kubectl get pods --all-namespaces | grep "prometheus-k8s-0")
if [ "${prometheus_pod_running}" == "" ]; then
	echo "Install prometheus required to fetch the resource usage metrics for kruize"
	exit 1

fi

KRUIZE_SETUP_LOG="${LOG_DIR}/kruize_setup.log"
KRUIZE_SERVICE_LOG="${LOG_DIR}/kruize_service.log"

# Setup kruize
if [ ${kruize_setup} == true ]; then
	echo "Setting up kruize..." | tee -a ${LOG}
	echo "$KRUIZE_REPO"
	pushd ${KRUIZE_REPO} > /dev/null
		# set 'local' flag to true and update datasource
		kruize_local_thanos_patch

        	echo "./deploy.sh -c ${CLUSTER_TYPE} -i ${KRUIZE_IMAGE} -m ${target} -t >> ${KRUIZE_SETUP_LOG}" | tee -a ${LOG}
		./deploy.sh -c ${CLUSTER_TYPE} -i ${KRUIZE_IMAGE} -m ${target} -t >> ${KRUIZE_SETUP_LOG} 2>&1

        	sleep 30
	        echo "./deploy.sh -c ${CLUSTER_TYPE} -i ${KRUIZE_IMAGE} -m ${target} >> ${KRUIZE_SETUP_LOG}" | tee -a ${LOG}
        	./deploy.sh -c ${CLUSTER_TYPE} -i ${KRUIZE_IMAGE} -m ${target} >> ${KRUIZE_SETUP_LOG} 2>&1 &
	        sleep 60

		# scale kruize pods
		echo "Scaling kruize replicas to ${replicas}..." | tee -a ${LOG}
		echo "kubectl scale deployments/kruize -n ${NAMESPACE} --replicas=${replicas}" | tee -a ${LOG}
		kubectl scale deployments/kruize -n ${NAMESPACE} --replicas=${replicas} | tee -a ${LOG}
		sleep 30

		echo "List the pods..." | tee -a ${LOG} | tee -a ${LOG}
		kubectl get pods -n ${NAMESPACE} | tee -a ${LOG}


	popd > /dev/null
	echo "Setting up kruize...Done" | tee -a ${LOG}
fi

if [ -z "${SERVER_IP_ADDR}" ]; then
	oc expose svc/kruize -n ${NAMESPACE}

	SERVER_IP_ADDR=($(oc status --namespace=${NAMESPACE} | grep "kruize" | grep port | cut -d " " -f1 | cut -d "/" -f3))
	port=0
	echo "SERVER_IP_ADDR = ${SERVER_IP_ADDR} " | tee -a ${LOG}
fi

echo | tee -a ${LOG}

get_kruize_pod_log ${LOG_DIR}
get_kruize_service_log ${KRUIZE_SERVICE_LOG}

if [ ${restore_db} == true ]; then
	# Load DB
	DB_RESTORE_LOG="${LOG_DIR}/db_restore.log"
	restore_db ${db_backup_file} ${DB_RESTORE_LOG}
fi

# Run the scale test
echo ""
echo "Running scale test for kruize on ${CLUSTER_TYPE}" | tee -a ${LOG}
echo ""
echo "nohup ./run_scalability_test.sh -c "${CLUSTER_TYPE}" -a "${SERVER_IP_ADDR}" -p "${port}" -x "${num_clusters}" -u "${num_namespaces}" -w "${num_workloads}" -d "${num_days_of_res}" -n "${num_clients}" -m "${minutes_jump}" -i "${interval_hours}" -s "${initial_start_date}" -q "${query_db_interval}" -r "${LOG_DIR}" -e "${total_results_count}" | tee -a ${LOG} "
nohup ./run_scalability_test.sh -c "${CLUSTER_TYPE}" -a "${SERVER_IP_ADDR}" -p "${port}" -x "${num_clusters}" -u "${num_namespaces}" -w "${num_workloads}" -d "${num_days_of_res}" -n "${num_clients}" -m "${minutes_jump}" -i "${interval_hours}" -s "${initial_start_date}" -q "${query_db_interval}" -r "${LOG_DIR}" -e "${total_results_count}" | tee -a ${LOG}

end_time=$(get_date)
elapsed_time=$(time_diff "${start_time}" "${end_time}")
echo ""
echo "Test took ${elapsed_time} seconds to complete" | tee -a ${LOG}
