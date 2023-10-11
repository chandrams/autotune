#!/bin/bash

cluster_type="openshift"
num_exps=250
num_days_of_res=2
num_clients=10
results_count=24
minutes_jump=15
initial_start_date="2023-08-01T00:00:00.000Z"
interval_hours=6

function usage() {
	echo
	echo "Usage: ./run_scalability_test.sh -c cluster_type[minikube|openshift (default - openshift)] [-a IP] [-p PORT] [-u No. of experiments per client (default - 250)]"
        echo "	     [-d No. of days of results (default - 2)] [-n No. of clients] [-m results duration interval] [-i interval hours] [-s Initial start date][-r <resultsdir path>]"
	exit -1
}

function query_db() {
	while(true); do
		exp_count=$(kubectl exec -it `kubectl get pods -o=name -n openshift-tuning | grep postgres` -n openshift-tuning -- psql -U admin -d kruizeDB -c "SELECT count(*) from public.kruize_experiments ;" | tail -3 | head -1 | tr -d '[:space:]')

		results_count=$(kubectl exec -it `kubectl get pods -o=name -n openshift-tuning | grep postgres` -n openshift-tuning -- psql -U admin -d kruizeDB -c "SELECT count(*) from public.kruize_results ;" | tail -3 | head -1 | tr -d '[:space:]')

		echo "Exps = $exp_count Results = $results_count"
		days_completed=$((${results_count} / (96 * ${num_exps} * ${num_clients})))
		day_in_progress=$(($days_completed + 1))

		if [ ${days_completed} == 0 ]; then
			echo "Day $day_in_progress in progress"
		else
			echo "Day $days_completed completed, Day $day_in_progress in progress"
		fi

		sleep 300
	done

}

function execution_time() {
	declare -a time_arr

	exec_time_log=$1
	scale_log_dir=$2
	time_arr=(1 4 28 40 60)

	cd $scale_log_dir

	for i in ${time_arr[@]}; do
		time_option="-m${i}"
		echo "" >> ${exec_time_log}
		if [ ${i} == 1 ]; then
			echo "6 hours" > ${exec_time_log}
		else
			j=$((${i}/4))
			if [ ${i} == 4 ]; then
				echo "${j} day" >> ${exec_time_log}
			else
				echo "${j} days" >> ${exec_time_log}
			fi
		fi
		echo "grep ${time_option} -H 'Time elapsed:' *.log | awk -F '[:.]' '{ sum[$1] += ($4 * 3600) + ($5 * 60) + $6 } END { for (key in sum) { printf "%s: Total time elapsed: %02d:%02d:%02d\n", key, sum[key] / 3600, (sum[key] / 60) % 60, sum[key] % 60 } }' | sort >> ${exec_time_log}"
		echo ""
		grep ${time_option} -H 'Time elapsed:' *.log | awk -F '[:.]' '{ sum[$1] += ($4 * 3600) + ($5 * 60) + $6 } END { for (key in sum) { printf "%s: Total time elapsed: %02d:%02d:%02d\n", key, sum[key] / 3600, (sum[key] / 60) % 60, sum[key] % 60 } }' | sort >> ${exec_time_log}
	done
}


while getopts c:a:p:r:u:n:d:m:i:s:h gopts
do
	case ${gopts} in
	c)
		cluster_type=${OPTARG}
		;;
	a)
		IP=${OPTARG}
		;;
	p)
		PORT=${OPTARG}
		;;
	r)
		RESULTS_DIR="${OPTARG}"		
		;;
	u)
		num_exps="${OPTARG}"		
		;;
	n)
		num_clients="${OPTARG}"		
		;;
	d)
		num_days_of_res="${OPTARG}"		
		;;
	m)
		minutes_jump="${OPTARG}"		
		;;
	i)
		interval_hours="${OPTARG}"		
		;;
	s)
		initial_start_date="${OPTARG}"		
		;;
	h)
		usage
		;;
	esac
done

if [ -z "${IP}" ]; then
	usage
fi

SCALE_LOG_DIR="${RESULTS_DIR}/scale_logs"
mkdir -p "${SCALE_LOG_DIR}"
echo "SCALE_LOG_DIR = $SCALE_LOG_DIR"

# Each loops kicks off the specified no. of experiments and posts results for the specified no. of days
#prometheus_server="kruiz.dsal.lab.eng.tlv2.redhat.com"
prometheus_server=$(echo ${IP} | cut -d "." -f 3- )

echo "Prometheus server = $prometheus_server"
declare -a pid_array=()
for ((loop=1; loop<=num_clients; loop++));
do

	name="scaletest${num_exps}-${num_days_of_res}days-${loop}"
	logfile="${SCALE_LOG_DIR}/${name}.log"
	echo "logfile = $logfile"
	sleep 5
	nohup ./rosSimulationScalabilityWrapper.sh --ip "${IP}" --port "${PORT}" --name scaletest${num_exps}-${num_days_of_res}days-${loop} --count ${num_exps},${results_count} --minutesjump ${minutes_jump} --initialstartdate ${initial_start_date} --limitdays ${num_days_of_res} --intervalhours ${interval_hours} --clientthread ${loop}  --prometheusserver ${prometheus_server} --outputdir ${RESULTS_DIR} >> ${logfile} 2>&1 &

	pid_array+=($!)

	echo
	echo "###########################################################################"
	echo "#                                                                         #"
	echo "#  Kicked off ${num_exps} experiments and data upload for client: ${loop} #"
	echo "#                                                                         #"
	echo "###########################################################################"
	echo

	sleep 60
done

query_db &
MYSELF=$!

for pid in "${pid_array[@]}"; do
	wait "$pid"
    	echo "Process with PID $pid has completed."
done

echo "###########################################################################"
echo "				All threads completed!                          "
echo "###########################################################################"

exec_time_log="${RESULTS_DIR}/exec_time.log"
cd $SCALE_LOG_DIR

echo "Capturing execution time in ${exec_time_log}..."
execution_time ${exec_time_log} ${SCALE_LOG_DIR}
sleep 5
echo ""
echo "Capturing execution time in ${exec_time_log}...done"

actual_results_count=$(kubectl exec -it `kubectl get pods -o=name -n openshift-tuning | grep postgres` -n openshift-tuning -- psql -U admin -d kruizeDB -c "SELECT count(*) from public.kruize_results ;" | tail -3 | head -1 | tr -d '[:space:]')

expected_results_count=$((${num_exps} * ${num_clients} * ${num_days_of_res} * 96))

j=0
while [[ ${expected_results_count} != ${actual_results_count} ]]; do
	echo ""
	echo "expected results count = $expected_results_count actual_results_count = $actual_results_count"
	actual_results_count=$(kubectl exec -it `kubectl get pods -o=name -n openshift-tuning | grep postgres` -n openshift-tuning -- psql -U admin -d kruizeDB -c "SELECT count(*) from public.kruize_results ;" | tail -3 | head -1 | tr -d '[:space:]')

	expected_results_count=$((${num_exps} * ${num_clients} * ${num_days_of_res} * 96))
	if [ ${j} == 2 ]; then
		break
	else
		sleep 10
	fi
	j=$((${j} + 1))
done

exps_count=$(kubectl exec -it `kubectl get pods -o=name -n openshift-tuning | grep postgres` -n openshift-tuning -- psql -U admin -d kruizeDB -c "SELECT count(*) from public.kruize_results ;" | tail -3 | head -1 | tr -d '[:space:]')
echo ""
echo "###########################################################################"
echo "Scale test completed!"
echo "exps_count = $exps_count results_count = $actual_results_count"
if [ ${expected_results_count} != ${actual_results_count} ]; then
	echo "Expected results count not found in kruize_results db table"
fi
echo "###########################################################################"
echo ""
kill $MYSELF 