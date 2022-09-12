declare -A expected_tunable_values

function get_expected_tunable_values() {
        input_json=$1
	trial_num=$2

        config=".[0].trials.${trial_num}.config"
	echo "config = $config"

        expected_tunable_values[mem_request]=$(cat ${input_json} | jq ''${config}'.requests.memory.amount')
        expected_tunable_values[mem_limit]=$(cat ${input_json} | jq ''${config}'.limits.memory.amount')
        expected_tunable_values[cpu_request]=$(cat ${input_json} | jq ''${config}'.requests.cpu.amount')
        expected_tunable_values[cpu_limit]=$(cat ${input_json} | jq ''${config}'.limits.cpu.amount')

	expected_env=$(cat ${input_json} | jq ${config}.env)

	envs=""

	echo "env array - ${expected_env[@]}"
	name=$(echo ${expected_env[0]} | jq '.[0].name')
	value=$(echo ${expected_env[0]} | jq '.[0].value')
	echo "env name - $name"
	echo "env value - ${value}"

	envs=$(echo ${value} | tr '\r\n' ' ')
		
        IFS=' ' read -r -a env <<<  ${envs}
        expected_tunable_no="${#env[@]}"

        echo "Expected tunable values..."
        echo "Memory request = ${expected_tunable_values[mem_request]}"
        echo "Memory limit = ${expected_tunable_values[mem_limit]}"
        echo "CPU request = ${expected_tunable_values[cpu_request]}"
        echo "CPU limit = ${expected_tunable_values[cpu_limit]}"

        echo "expected tunable no = ${expected_tunable_no}"
}

get_expected_tunable_values "ab1.json" "A"
