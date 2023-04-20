"""
Copyright (c) 2022, 2022 Red Hat, IBM Corporation and others.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

import sys, getopt
import json
import os
import time
sys.path.append("..")
from helpers.kruize import *
from helpers.utils import *
from helpers.generate_rm_jsons import *

def main(argv):
    cluster_type = "minikube"
    results_dir = "."
    hours = 6
    try:
        opts, args = getopt.getopt(argv,"h:c:a:u:r:d:")
    except getopt.GetoptError:
        print("rm_scale_demo.py -c <cluster type> -a <openshift kruize route> -u <no. of experiments> -d <hours of data available> -r <results dir>")
        print("Note: -a option is required only on openshift when kruize service is exposed")
        sys.exit(2)
    for opt, arg in opts:
        if opt == '-h':
            print("rm_scale_demo.py -c <cluster type> -a <openshift kruize route> -u <no. of experiments> -d <hours of data available> -r <results dir>")
            sys.exit(0)
        elif opt == '-c':
            cluster_type = arg
        elif opt == '-a':
            server_ip_addr = arg
        elif opt == '-u':
            num_exps = int(arg)
        elif opt == '-r':
            results_dir = arg
        elif opt == '-d':
            hours = int(arg)
        

    print(f"Cluster type = {cluster_type}")
    print(f"No. of experiments = {num_exps}")
    print(f"Results dir = {results_dir}")

    # Form the kruize url
    if cluster_type == "minikube":
        form_kruize_url(cluster_type)
    else:
        form_kruize_url(cluster_type, server_ip_addr)

    # Create the performance profile
    perf_profile_json_file = "../json_files/resource_optimization_openshift.json"
    create_performance_profile(perf_profile_json_file)

    # Generate pay load 
    csv_filename = "../csv_data/tfb_data.csv"
    split = False
    split_count = 1

    exp_json_dir = results_dir + "/exp_jsons" + "_" + str(num_exps)
    result_json_dir = results_dir + "/result_jsons" + "_" + str(num_exps)
    reco_json_dir = results_dir + "/reco_jsons_" + str(num_exps)
    os.mkdir(reco_json_dir)

    exp_list = []

    iterations = int(hours / 6)
    # 6 hour results of each result with 15mins duration, so no. of results 6 * 4
    num_res = 24 


    for i in range(1, iterations+1):
        print("\n*************************")
        print(f"Iteration {i}...")
        print("*************************\n")
        create_exp_jsons(split, split_count, exp_json_dir, total_exps)

        if i == 1:
            #tmp_result_json_dir = result_json_dir + "_" + str(i)
            new_timestamp = None
            #create_update_results_jsons(csv_filename, split, split_count, tmp_result_json_dir, total_exps, num_res, new_timestamp)
            create_update_results_jsons(csv_filename, split, split_count, result_json_dir, total_exps, num_res, new_timestamp)
            start_ts = get_datetime()
        else:
            # Increment the time by 365 mins or 6 hrs 6 mins for the next set of data timestamps
            new_timestamp = increment_timestamp(start_ts, 365)
            start_ts = new_timestamp
            #tmp_result_json_dir = result_json_dir + "_" + str(i)
            #create_update_results_jsons(csv_filename, split, split_count, tmp_result_json_dir, total_exps, num_res, new_timestamp)
            create_update_results_jsons(csv_filename, split, split_count, result_json_dir, total_exps, num_res, new_timestamp)

        for exp_num in range(num_exps):
            # create the experiment and post it
            create_exp_json_file = exp_json_dir + "/create_exp_" + str(exp_num) + ".json"
            create_experiment(create_exp_json_file)
   
            json_data = json.load(open(create_exp_json_file))

            experiment_name = json_data[0]['experiment_name']
            exp_list.append(experiment_name)

            for res_num in range(num_res):
                # update 6 hours result for the specified experiment
                #json_file = tmp_result_json_dir + "/result_" + str(exp_num) + "_" + str(res_num) + ".json"
                json_file = result_json_dir + "/result_" + str(res_num) + ".json"
                update_results(json_file)

        # sleep for a while before fetching recommendations for the experiments
        time.sleep(60)

        # Fetch the recommendations for all the experiments
        for experiment_name in exp_list:
            latest = False
            reco = list_recommendations(experiment_name, latest)

            filename = reco_json_dir + '/reco_' + experiment_name + '.json'
            write_json_data_to_file(filename, reco.json())

        # sleep for a while to mimic the availability of next set of results
        time.sleep(1)

if __name__ == '__main__':
    main(sys.argv[1:])
