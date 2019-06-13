#!/bin/sh
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


tmp="$(mktemp -d "${TMPDIR:-/tmp}/list_generated_files.XXXXXXXX")"
[[ "${tmp}" != "" ]] || exit 1
trap "rm -rf ${tmp}" EXIT

base="${PWD}"
export LC_ALL=C

cd "${tmp}"
cp "${base}/java/google/registry/xjc/bindings.xjb" .
cp "${base}"/java/google/registry/xml/xsd/*.xsd .
"${base}/third_party/java/jaxb/jaxb-xjc" -extension -d "${tmp}" -b *.xjb *.xsd \
  | sed -ne s@google/registry/xjc/@@p \
  | grep -v package-info.java \
  | sort \
  > xjc_generated_files

cat <<EOF
#
#      .'\`\`'.      ...
#     :o  o \`....'\`  ;
#     \`. O         :'
#       \`':          \`.
#         \`:.          \`.
#          : \`.         \`.
#         \`..'\`...       \`.
#                 \`...     \`.
#  DO NOT EDIT        \`\`...  \`.
#         THIS FILE        \`\`\`\`\`.
#
# When you make changes to the XML schemas (*.xsd) or the JAXB bindings file
# (bindings.xjb), you must regenerate this file with the following commands:
#
#   bazel run java/google/registry/xjc:list_generated_files | tee /tmp/lol
#   mv /tmp/lol java/google/registry/xjc/generated_files.bzl
#
EOF

echo
echo "pkginfo_generated_files = ["
while read package; do
  printf '    "%s/package-info.java",\n' "${package}"
done < <(awk -F/ '{print $1}' xjc_generated_files | sort -u)
echo "]"

echo
echo "xjc_generated_files = ["
while read path; do
  printf '    "%s",\n' "${path}"
done <xjc_generated_files
echo "]"
