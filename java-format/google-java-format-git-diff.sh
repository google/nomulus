#!/bin/bash
# Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
# This script applies Google Java format to modified regions in Java source
# files in a Git repository. It assumes that the repository has a 'master'
# branch that is only used for merging and is never directly worked on.
#
# If invoked on the master branch, this script will format the modified lines
# relative to HEAD. Otherwise, it uses the
# 'git merge-base --fork-point origin/master' command to find the latest
# fork point from origin/master, and formats the modified lines between
# the fork point and the HEAD of the current branch.
#
# Background: existing code base does not conform to Google Java format. Since
# the team wants to keep the 'blame' feature (find last modifier of a line)
# usable, we do not want to reformat existing code.

set -e

USAGE="
$(basename "$0") [--help] check|format|show
Incrementally format modified java lines in Git.

where:
    --help  show this help text
    check  check if formatting is necessary
    format format files in place
    show   show the effect of the formatting as unified diff"

SCRIPT_DIR="$(realpath $(dirname $0))"
JAR_NAME="google-java-format-1.8-all-deps.jar"

# Make sure we have a valid python interpreter.
if [ -z "$PYTHON" ]; then
  echo "You must specify the name of a python3 interpreter in the PYTHON" \
       "environment variable."
  exit 1
elif ! "$PYTHON" -c ''; then
  echo "Invalid python interpreter: $PYTHON"
  exit 1
fi

# Locate the java binary.
if [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVA_BIN" ]; then
    echo "No java binary found in JAVA_HOME (JAVA_HOME is $JAVA_HOME)"
    exit 1
  fi
else
  # Use java from the path.
  JAVA_BIN="$(which java)" || JAVA_BIN=""
  if [ -z "$JAVA_BIN" ]; then
    echo "JAVA_HOME is not defined and java was not found on the path"
    exit 1
  fi
fi

if ! "$JAVA_BIN" -version 2>&1 | grep 'version "11\.' >/dev/null; then
  echo "Bad java version.  Requires java 11, got:"
  "$JAVA_BIN" -version
  exit 1
fi

function runGoogleJavaFormatAgainstDiffs() {
  local forkPoint="$1"
  shift

  git diff -U0 "$forkPoint" | \
      "${PYTHON}" "${SCRIPT_DIR}/google-java-format-diff.py" \
          --java-binary "$JAVA_BIN" \
          --google-java-format-jar "${SCRIPT_DIR}/${JAR_NAME}" \
          -p1 "$@" | \
      tee gjf.out

  # If any of the commands in the last pipe failed, return false.
  [[ ! "${PIPESTATUS[@]}" =~ [^0\ ] ]]
}

# Show the file names in a diff preceeded by a message.
function showFileNames() {
  local message="$1"

  awk -v "message=$message" '/\+\+\+ ([^ ]*)/ { print message $2 }' 1>&2
}

function showNoncompliantFiles() {
  local forkPoint="$1"
  local message="$2"

  runGoogleJavaFormatAgainstDiffs "$forkPoint" | showFileNames "$2"
}

function callGoogleJavaFormatDiff() {
  local forkPoint
  forkPoint=$(git merge-base origin/master HEAD)

  local callResult
  case "$1" in
    "check")
      # We need to do explicit checks for an error and "exit 1" if there was
      # one here (though not elsewhere), "set -e" doesn't catch this case,
      # it's not clear why.
      local output
      output=$(runGoogleJavaFormatAgainstDiffs "$forkPoint") || exit 1
      echo "$output" | showFileNames "\033[1mNeeds formatting: "
      callResult=$(echo -n "$output" | wc -l)
      ;;
    "format")
      # Unfortunately we have to do this twice if we want to see the names of
      # the files that got reformatted
      showNoncompliantFiles "$forkPoint" "\033[1mReformatting: "
      callResult=$(runGoogleJavaFormatAgainstDiffs "$forkPoint" -i)
      ;;
    "show")
      callResult=$(runGoogleJavaFormatAgainstDiffs "$forkPoint")
      ;;
  esac
  echo -e "\033[0m" 1>&2
  echo "${callResult}"
  exit 0
}

function isJavaFormatNeededOnDiffs() {
  local modifiedLineCount
  modifiedLineCount=$(callGoogleJavaFormatDiff "check")

  if [[ ${modifiedLineCount} -ne 0 ]]; then
    echo "true"
  else
    echo "false"
  fi
  exit 0
}

# The main function of this script:
if [[ $# -eq 1 && $1 == "check" ]]; then
  isJavaFormatNeededOnDiffs
elif [[ $# -eq 1 && $1 == "format" ]]; then
  callGoogleJavaFormatDiff "format"
elif [[ $# -eq 1 && $1 == "show" ]]; then
  callGoogleJavaFormatDiff "show"
else
  echo "${USAGE}"
fi
