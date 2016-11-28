#!/bin/bash

if [ -z "$1" ]; then
  echo "usage: build-deploy-artifact.sh ear_dir [environment]"
  echo "    ear_dir: Directory where the .ear file will be expanded.  Required."
  echo "    environment: The environment the build should target (\"crash\", e.g.).  Defaults to production environment."
  exit 1
fi
EAR_DIR=$1

ENV_SUFFIX=""
if [ -n "$2" ]; then
  echo "building for $2 environment"
  if [ "$2" != "production" ]; then
    ENV_SUFFIX="_$2"
  fi
fi

if ! bazel build --javacopt "-source 1.7" --javacopt "-target 1.7" //java/google/registry:registry${ENV_SUFFIX}.ear; then
  echo "Build failed"
  exit 1
fi

echo "Extracting .ear file to" $PWD/$EAR_DIR
rm -rf $EAR_DIR
mkdir $EAR_DIR
cd $EAR_DIR
jar -xf ../bazel-genfiles/java/google/registry/registry${ENV_SUFFIX}.ear
cd $OLDPWD
