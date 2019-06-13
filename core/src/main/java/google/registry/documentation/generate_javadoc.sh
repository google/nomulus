#!/bin/bash
# Copyright 2017 The Nomulus Authors. All Rights Reserved.
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


# Generate javadoc for the project

if (( $# != 3 )); then
  echo "Usage: $0 JAVADOC ZIP OUT" 1>&2
  exit 1
fi

JAVADOC_BINARY="$1"
ZIP_BINARY="$2"
TARGETFILE="$3"
TMPDIR="$(mktemp -d "${TMPDIR:-/tmp}/generate_javadoc.XXXXXXXX")"
PWDDIR="$(pwd)"

"${JAVADOC_BINARY}" -d "${TMPDIR}" \
  $(find java -name \*.java) \
  -tag error:t:'EPP Errors' \
  -subpackages google.registry \
  -exclude google.registry.dns:google.registry.proxy:google.registry.monitoring.blackbox
cd "${TMPDIR}"
"${PWDDIR}/${ZIP_BINARY}" -rXoq "${PWDDIR}/${TARGETFILE}" .
cd -
rm -rf "${TMPDIR}"
