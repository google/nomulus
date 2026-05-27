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

# Wrapper script to dynamically execute OpenRewrite without modifying build.gradle

if [ -z "$1" ]; then
  echo "Usage: $0 <path-to-rewrite.yml>"
  exit 1
fi

RECIPE_FILE=$(realpath "$1")
if [ ! -f "$RECIPE_FILE" ]; then
  echo "Error: Recipe file $RECIPE_FILE not found."
  exit 1
fi

# Extract the name of the recipe from the YAML to activate it
RECIPE_NAME=$(grep -oP '(?<=name: ).*' "$RECIPE_FILE" | head -n 1)

if [ -z "$RECIPE_NAME" ]; then
  echo "Error: Could not extract 'name:' from $RECIPE_FILE"
  exit 1
fi

INIT_SCRIPT="rewrite-init.gradle"

cat << EOF > "$INIT_SCRIPT"
initscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.openrewrite.rewrite:org.openrewrite.rewrite.gradle.plugin:7.33.0")
    }
}

rootProject {
    apply plugin: org.openrewrite.gradle.RewritePlugin

    rewrite {
        activeRecipe("$RECIPE_NAME")
    }

    dependencies {
        rewrite("org.openrewrite.recipe:rewrite-testing-frameworks:2.14.0")
        rewrite("org.openrewrite.recipe:rewrite-migrate-java:2.11.0")
        rewrite("org.openrewrite.recipe:rewrite-spring:5.7.0")
    }
}

allprojects {
    apply plugin: org.openrewrite.gradle.RewritePlugin
}
EOF

# Copy the recipe file to the workspace root temporarily so OpenRewrite finds it
cp "$RECIPE_FILE" ./rewrite.yml

echo "Executing OpenRewrite recipe: $RECIPE_NAME"
./gradlew --init-script "$INIT_SCRIPT" rewriteRun --no-parallel --no-configuration-cache

echo "Running code formatters to fix Checkstyle line-length and import ordering..."
./gradlew spotlessApply

# Automatically handle line-wrapping and formatting for all files modified by OpenRewrite
MODIFIED_JAVA_FILES=$(git diff --name-only --diff-filter=d | grep "\.java$" || true)
if [ -n "$MODIFIED_JAVA_FILES" ]; then
  echo "Applying google-java-format to all modified Java files to enforce LineLength..."
  echo "$MODIFIED_JAVA_FILES" | xargs -r google-java-format --replace
fi

# Clean up temporary files
rm "$INIT_SCRIPT"
rm ./rewrite.yml
