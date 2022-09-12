# testcases for em_invalid_input_json_tests
AUTOTUNE_REPO="/home/csubrama/github/chandra/autotune-mvp-demo/autotune"
em_input_json_tests=(

"datasource_provider"
"datasource_url"

)

datasource_provider_testcases=(

blank_datasource_provider
invalid_datasource_provider
)

datasource_url_testcases=(
blank_datasource_url
invalid_datasource_url
)

declare -A em_input_json_find=(
[datasource_provider]='"provider": "prometheus"' 
[datasource_url]='"url": "http://10.101.144.137:9090"'

)

declare -A datasource_provider_replace=(

[blank_datasource_provider]='"provider": ""' 
[invalid_datasource_provider]='provider: "xyz"'
) 

declare -A datasource_url_replace=(
[blank_datasource_url]='"url": ""'
[invalid_datasource_url]='url": "http://xyz.com"'

)

function generate_exp_input_jsons() {
	testtorun=("$@")


	echo "Testtorun = ${testtorun[@]}"
	for test in "${testtorun[@]}"
	do
		echo "Test = $test"
		LOG_DIR="${TEST_SUITE_DIR}/${test}"
		mkdir -p ${LOG_DIR}

		typeset -n find="em_input_json_find[${test}]"
#		find_name='name: "petclinic-autotune"'
		reference_json="${AUTOTUNE_REPO}/examples/em-jsontemplates/ABTestingTemplate.json"


#		typeset -n var="${test}_testcases"
		echo "test ${test}"
		typeset -n test_names="${test}_testcases"
		echo "test_names ${test_names}"
		for testcase in ${test_names[@]}
		do
			test_json=${LOG_DIR}/${testcase}
			echo ""
			echo "testcase ${testcase}"
			echo "${test_json}.json"
			# Keep a copy of the relevant yaml to perform the test
			cp  "${reference_json}" "${test_json}/${test_json}.json"

			# Find and replace the metadata name according to the test
#			replace_name='name: "'${testcase}'"'
#
#			echo "find_name ${find_name}"
#			echo "replace_name ${replace_name}"

			# Update the json with specified field
#			sed -i "s|${find_name}|${replace_name}|g" "${test_json}/${test_json}.json"

			# find and replace the mentioned field accor
			typeset -n replace="${test}_replace[${testcase}]"

			echo "find ${find}"
			echo "replace ${replace}"

			sed -i "s|${find}|${replace}|g" ${test_json}.json
		done
	done
}

TEST_SUITE_DIR="generate"
mkdir -p ${TEST_SUITE_DIR}
generate_exp_input_jsons "${em_input_json_tests[@]}"

