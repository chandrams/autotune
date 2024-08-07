"""
Copyright (c) 2024, 2024 Red Hat, IBM Corporation and others.

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

import csv
import os
import argparse
import re

def max_time(time_list):
    max_seconds = -1
    max_time_str = ""

    for time_str in time_list:
        hours, minutes, seconds = map(int, time_str.split(':'))
        total_seconds = hours * 3600 + minutes * 60 + seconds
        if total_seconds > max_seconds:
            max_seconds = total_seconds
            max_time_str = time_str

    return max_time_str

def find_max_exec_time(exec_file):
    # Define the pattern to match
    pattern = r"scaletest\d+-\d+: Total time elapsed: (\d{2,3}:\d{2}:\d{2})"

    with open(exec_file, "r") as file:
        lines = file.readlines()

    filtered_lines = []

    # Iterate through lines to find pattern match
    for line in lines:
        match = re.search(pattern, line)
        if match:
            time_elapsed = match.group(1)
            print(time_elapsed)
            filtered_lines.append(time_elapsed)

    print(f"Execution time - {max_time(filtered_lines)}")


target_value_to_find = 144000000

print(f"Results count - {target_value_to_find}")

exec_time_log = "./exec_time.log"
find_max_exec_time(exec_time_log)

