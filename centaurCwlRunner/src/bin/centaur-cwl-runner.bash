#!/usr/bin/env bash

set -x

echo Java options is "${_JAVA_OPTIONS:-unset}" 1>&2
unset _JAVA_OPTIONS
echo Java options reset to "${_JAVA_OPTIONS:-unset}" 1>&2

# `sbt assembly` must have already been run.
BUILD_ROOT="$( dirname "${BASH_SOURCE[0]}" )/../../.."
CENTAUR_CWL_JAR="${CENTAUR_CWL_JAR:-"$( find "${BUILD_ROOT}/centaurCwlRunner/target/scala-2.12" -name 'centaur-cwl-runner-*.jar' | head -n 1 )"}"
CENTAUR_CWL_SKIP_FILE="${BUILD_ROOT}/centaurCwlRunner/src/main/resources/skipped_tests.csv"

echo "Jar:" 1>&2
ls -al "${CENTAUR_CWL_JAR}" 1>&2
echo "Skip file:" 1>&2
ls -al "${CENTAUR_CWL_SKIP_FILE}" 1>&2

echo "Pwd is:" 1>&2
pwd 1>&2

free -m 1>&2

echo "Args are:" 1>&2
echo "$@" 1>&2

echo "Running java to get version:" 1>&2
java ${CENTAUR_CWL_JAVA_ARGS-"-Xmx1g"} -jar "${CENTAUR_CWL_JAR}" --version 1>&2
result=$?
echo FINDM4 result = $result 1>&2

echo "Running java:" 1>&2
ls -l 1>&2
java ${CENTAUR_CWL_JAVA_ARGS-"-Xmx1g"} -jar "${CENTAUR_CWL_JAR}" --skip-file "${CENTAUR_CWL_SKIP_FILE}" "$@"
result=$?
ls -l 1>&2
echo FINDM2 result = $result 1>&2
exit $result
