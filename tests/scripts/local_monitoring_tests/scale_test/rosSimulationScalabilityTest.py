import argparse
import copy
import datetime
import json
import time

import requests


def loadData():
    json_file = open("./json_files/create_exp.json", "r")
    createdata = json.loads(json_file.read())

    json_file = open("./json_files/resource_optimization_openshift.json", "r")
    profile_data = json.loads(json_file.read())

    return (createdata, profile_data)

def generateRecommendation(experiment_name):
    try:
        # Send the request with the payload
        payloadRecommendationURL = "%s?experiment_name=%s" % (
            generateRecommendationURL, experiment_name)
        response = requests.post(payloadRecommendationURL, data={}, headers=headers, timeout=timeout)
        # Check the response
        if response.status_code == 201:
            data = response.json()
            print(data)
            #print('experiment_name %s  : %s' % (experiment_name , data[0]['kubernetes_objects'][0]['containers'][0]['recommendations']['notifications']['112101'][
            #    'message'] ))
            pass
        else:
            print(
                f'{payloadRecommendationURL} Request failed with status code {response.status_code}: {response.text}')
            #requests.post(createProfileURL, data=profile_json_payload, headers=headers)
    except requests.exceptions.Timeout:
        print('generateRecommendation Timeout occurred while connecting to')
    except requests.exceptions.RequestException as e:
        print('generateRecommendation Timeout occurred while connecting to', e)

if __name__ == "__main__":
    debug = False
    # create an ArgumentParser object
    parser = argparse.ArgumentParser()

    # add the named arguments
    parser.add_argument('--ip', type=str, help='specify kruize  ip')
    parser.add_argument('--port', type=int, help='specify port')
    parser.add_argument('--name', type=str, help='specify experiment name')
    parser.add_argument('--count', type=str,
                        help='specify input the number of experiments and corresponding results, separated by commas.')
    parser.add_argument('--startdate', type=str, help='Specify start date and time in  "%Y-%m-%dT%H:%M:%S.%fZ" format.')
    parser.add_argument('--minutesjump', type=int,
                        help='specify the time difference between the start time and end time of the interval.')

    # parse the arguments from the command line
    args = parser.parse_args()
    if args.port != 0:
        createExpURL = 'http://%s:%s/createExperiment' % (args.ip, args.port)
        createProfileURL = 'http://%s:%s/createMetricProfile' % (args.ip, args.port)
        generateRecommendationURL = 'http://%s:%s/generateRecommendations' % (args.ip, args.port)
    else:
        createExpURL = 'http://%s/createExperiment' % (args.ip)
        createProfileURL = 'http://%s/createMetricProfile' % (args.ip)
        generateRecommendationURL = 'http://%s/generateRecommendations' % (args.ip)

    expnameprfix = args.name
    nscount = int(args.count.split(',')[0])
    wlcount = int(args.count.split(',')[1])
    expcount = nscount * wlcount
    print(expcount)
    
    minutesjump = args.minutesjump
    headers = {
        'Content-Type': 'application/json'
    }
    timeout = (60, 60)
    createdata, profile_data = loadData()


    if debug:
        print(createExpURL)
        print(createProfileURL)
        print("experiment_name : %s " % (expnameprfix))
        print("Number of experiments to create : %s" % (expcount))

    # Create a performance profile
    profile_json_payload = json.dumps(profile_data)
    response = requests.post(createProfileURL, data=profile_json_payload, headers=headers)
    if response.status_code == 201:
        if debug: print('Request successful!')
        if nscount > 10 : time.sleep(5)
    else:
        if debug: print(f'Request failed with status code {response.status_code}: {response.text}')

    createExp_time = 0.0
    updateRec_time = 0.0

    # Create experiment and post results
    start_time = time.time()

    for i in range(0, nscount):
        for j in range(0, wlcount):
            createExp_elapsed_time = 0
            updateRec_elapsed_time = 0
            try:
                successfulCnt = 0
                experiment_name = "%s_%d_%d" % (expnameprfix, i, j)

                createdata['experiment_name'] = experiment_name
                createdata['cluster_name'] = "eu-1"

                createdata['kubernetes_objects'][0]['name'] = "tfb-qrh-sample-%s" % (j)

                createdata['kubernetes_objects'][0]['namespace'] = "msc-%s" % (i)
                createdata['kubernetes_objects'][0]['containers'][0]['container_name'] = "tfb-%s" % (j)

                create_json_payload = json.dumps([createdata])
                print(createdata)

                # Create experiment
                createExp_start_time = time.time()
                print(createExp_start_time)
                response = requests.post(createExpURL, data=create_json_payload, headers=headers, timeout=timeout)
                createExp_elapsed_time = time.time() - createExp_start_time
                print(createExp_elapsed_time)

                if response.status_code == 201 or response.status_code == 409 or response.status_code == 400:
                    time.sleep(60)
                    updateRec_start_time = time.time()
                    print("before generate reco")
                    generateRecommendation(experiment_name)
                    print("after generate reco")
                    updateRec_elapsed_time = time.time() - updateRec_start_time
                else:
                    print(f'Request failed with status code {response.status_code}: {response.text}')
            except requests.exceptions.Timeout:
                print('Timeout occurred while connecting to')
            except requests.exceptions.RequestException as e:
                print('An error occurred while connecting to', e)
            except Exception as e:
                    print('An error occurred ', e)

            createExp_time += createExp_elapsed_time
            updateRec_time += updateRec_elapsed_time

           
    elapsed_time = time.time() - start_time

    hours, rem = divmod(elapsed_time, 3600)
    minutes, seconds = divmod(rem, 60)
    print("Time elapsed: {:0>2}:{:0>2}:{:05.2f}".format(int(hours), int(minutes), seconds))

    hours, rem = divmod(createExp_time, 3600)
    minutes, seconds = divmod(rem, 60)
    print("createExp elapsed time: {:0>2}:{:0>2}:{:05.2f}".format(int(hours), int(minutes), seconds))

    hours, rem = divmod(updateRec_time, 3600)
    minutes, seconds = divmod(rem, 60)
    print("generateRec elapsed time: {:0>2}:{:0>2}:{:05.2f}".format(int(hours), int(minutes), seconds))
