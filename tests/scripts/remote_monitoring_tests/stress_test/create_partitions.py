import datetime
import json

import sys, getopt
sys.path.append('..')

from helpers.generate_rm_jsons import *
from helpers.kruize import *
from helpers.utils import *

def upload_data(num_res, cluster_type):
    input_json_file = "../json_files/create_exp.json"
    result_json_file = "../json_files/update_results.json"

    find = []
    json_data = json.load(open(input_json_file))

    find.append(json_data[0]['experiment_name'])
    find.append(json_data[0]['kubernetes_objects'][0]['name'])
    find.append(json_data[0]['kubernetes_objects'][0]['namespace'])

    form_kruize_url(cluster_type)

    # Create experiment using the specified json
    num_exps = 1
    list_of_result_json_arr = []
    for i in range(num_exps):
        create_exp_json_file = "/tmp/create_exp_" + str(i) + ".json"
        generate_json(find, input_json_file, create_exp_json_file, i)

        response = delete_experiment(create_exp_json_file)
        print("delete exp = ", response.status_code)

        response = create_experiment(create_exp_json_file)
        if response.status_code != SUCCESS_STATUS_CODE:
            print("Experiment creation failed!")

        # Update results for the experiment
        update_results_json_file = "/tmp/update_results_" + str(i) + ".json"

        result_json_arr = []
        complete_result_json_arr = []
        total_res = 0
        bulk_res = 0

        interval_start_time = get_datetime()

        for j in range(num_res):
            update_timestamps = True
            generate_json(find, result_json_file, update_results_json_file, i, update_timestamps)
            result_json = read_json_data_from_file(update_results_json_file)

            if j == 0:
                start_time = interval_start_time
            else:
                start_time = end_time


            result_json[0]['interval_start_time'] = start_time
            end_time = increment_timestamp_by_given_mins(start_time, 15)
            result_json[0]['interval_end_time'] = end_time

            result_json_arr.append(result_json[0])
            complete_result_json_arr.append(result_json[0])

            result_json_arr_len = len(result_json_arr)

            if result_json_arr_len == 100:
                print(f"Updating {result_json_arr_len} results...")
                # Update results for every 100 results
                bulk_update_results_json_file = "/tmp/bulk_update_results_" + str(i) + "_" + str(bulk_res) + ".json"

                write_json_data_to_file(bulk_update_results_json_file, result_json_arr)
                response = update_results(bulk_update_results_json_file)
                data = response.json()

                if response.status_code != SUCCESS_STATUS_CODE:
                    print("Results upload failed!")

                result_json_arr = []
                total_res = total_res + result_json_arr_len
                bulk_res = bulk_res + 1

        result_json_arr_len = len(result_json_arr)

        if result_json_arr_len != 0:
            print(f"Updating {result_json_arr_len} results...")
            bulk_update_results_json_file = "/tmp/bulk_update_results_" + str(i) + "_" + str(bulk_res) + ".json"
            write_json_data_to_file(bulk_update_results_json_file, result_json_arr)

            response = update_results(bulk_update_results_json_file)

            data = response.json()

            if response.status_code != SUCCESS_STATUS_CODE:
                print("Results upload failed!")

            total_res = total_res + result_json_arr_len
            
        print(f"total_results = {total_res} end_time = {end_time}")


def main(argv):
    cluster_type = "minikube"
    num_res = 3100
    failed = 0

    try:
        opts, args = getopt.getopt(argv,"h:a:c:n:")
    except getopt.GetoptError:
        print("create_partitions.py -c <cluster type> -a <openshift kruize route> -n <number of results>")
        print("Note: -a option is required only on openshift when kruize service is exposed")
        sys.exit(2)
    for opt, arg in opts:
        if opt == '-h':
            print("create_partitions.py -c <cluster type> -a <openshift kruize route> -n <number of results>")
            sys.exit(0)
        elif opt == '-c':
            cluster_type = arg
        elif opt == '-a':
            server_ip_addr = arg
        elif opt == '-n':
            num_res = int(arg)


    print(f"Cluster type = {cluster_type}")
    print(f"No. of results = {num_res}")

    # Form the kruize url
    if cluster_type == "minikube":
        form_kruize_url(cluster_type)
        namespace = "monitoring"
    else:
        form_kruize_url(cluster_type, server_ip_addr)
        namespace = "openshift-tuning"

    upload_data(num_res, cluster_type)

if __name__ == '__main__':
    main(sys.argv[1:])
                           
