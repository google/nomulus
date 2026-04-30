#!/bin/bash
# Copyright 2026 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# This script runs the BEAM pipeline smoke test as part of the build process.
# It assumes that all pipelines have been built and staged.
#
# This script expects the following arguments in order:
# - The release tag
# - The GCP project that serves the release artifacts
# - The GCP project id where the smoke test should run. This id should have the
#   Nomulus environment name as suffix, following the last '-' in the project id,
#   E.g., domain-registry-crash. Only crash and sandbox are allowed as the test
#   environment. Use 'NONE' as project id to skip this test.
# - GCP region to run the test. This is optional.

set -e

if [[ $# -ne 3 && $# -ne 4 ]];
then
  echo "Usage: $0 <release_tag> <build_project_id> <test_project_id> [<gcp_region>]"
  exit 1
fi

release_tag="$1"
build_project="$2"
test_project="$3"
region="${4:-us-central1}"

if [[ "${test_project}" == "NONE" ]]; then
  echo "BEAM smoke test skipped as requested."
  exit 0
fi

test_project_id_suffix="${test_project##*-}"
if [[ "${test_project}" == *"-"* ]]; then
  # Convert environment name to upper case
  test_env="${test_project_id_suffix^^}"
else
  test_env=""
fi

if [[ -z "${test_env}" ]]; then
  echo "Cannot extract environment from project id ${test_project}"
  exit 1
fi

if [[ ${test_env} != "CRASH" && ${test_env} != "SANDBOX" ]]; then
  echo "Expecting CRASH or SANDBOX as test environment, got ${test_env}"
  exit 1
fi

template_folder="gs://${build_project}-deploy/${release_tag}/beam"
template="${template_folder}/smoke_test_pipeline_metadata.json"
job_name=$(echo "beam-smoketest-${release_tag}" | tr '[:upper:]_' '[:lower:]-')
job_id=$(gcloud dataflow flex-template run "${job_name}" \
    --template-file-gcs-location="${template}" \
    --region="${region}" --parameters=registryEnvironment="${test_env}" \
    --project=${test_project} --format='value(job.id)')
echo "Test pipeline started as ${job_id}"

# Wait up to 30 minutes for the smoke test to finish. This 5X of a typical
# run.
for i in {1..30}
do
  job_state=$(gcloud dataflow jobs describe "${job_id}" --region=${region} \
      --format="value(currentState)" --project "${test_project}")
  echo "Test pipeline state is ${job_state}"

  if [[ "${job_state}" == "JOB_STATE_DONE" ]]; then
    echo "Smoke test completed successfully."
    exit 0
  elif [[ "${job_state}" == "JOB_STATE_QUEUED" || \
        "${job_state}" == "JOB_STATE_RUNNING" || \
        "${job_state}" == "JOB_STATE_PENDING" ]]; then
      echo "Sleeping for 60 seconds"
      sleep 60
  else
      echo "Unexpected job state ${job_state}"
      exit 1
  fi
done

echo "Error: Smoke test did not complete in time."
exit 1
