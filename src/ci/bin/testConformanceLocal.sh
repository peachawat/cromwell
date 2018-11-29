#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail
# import in shellcheck / CI / IntelliJ compatible ways
# shellcheck source=/dev/null
source "${BASH_SOURCE%/*}/test.inc.sh" || source test.inc.sh

cromwell::build::setup_common_environment

cromwell::build::setup_conformance_environment

cromwell::build::assemble_jars

(
    set +e
    set -x
    free -m
    java -Xmx1g -jar ${CROMWELL_BUILD_ROOT_DIRECTORY}/centaurCwlRunner/target/scala-2.12/centaur-cwl-runner-37-8732448-SNAP.jar --skip-file centaurCwlRunner/src/bin/../../../centaurCwlRunner/src/main/resources/skipped_tests.csv --version
    echo exit code
    echo $?

    cd /home/travis/build/broadinstitute/cromwell/common-workflow-language/v1.0


    echo "Cwltool info:"
    cwltool --version
    cwltool --print-pre v1.0/bwa-mem-tool.cwl

    mkdir mytmpdir

    java -Xmx1g \
      -jar /home/travis/build/broadinstitute/cromwell/centaurCwlRunner/src/bin/../../../centaurCwlRunner/target/scala-2.12/centaur-cwl-runner-37-8732448-SNAP.jar \
      --skip-file /home/travis/build/broadinstitute/cromwell/centaurCwlRunner/src/bin/../../../centaurCwlRunner/src/main/resources/skipped_tests.csv \
      --outdir=$PWD/mytmpdir \
      --quiet \
      v1.0/bwa-mem-tool.cwl \
      v1.0/bwa-mem-job.json

    set -e
)

CENTAUR_CWL_RUNNER_MODE="local"

# Export variables used in conf files and commands
export CENTAUR_CWL_RUNNER_MODE

shutdown_cromwell() {
    if [ -n "${CROMWELL_PID+set}" ]; then
        cromwell::build::kill_tree "${CROMWELL_PID}"
    fi
}

cromwell::build::add_exit_function shutdown_cromwell

# Start the Cromwell server in the directory containing input files so it can access them via their relative path
cd "${CROMWELL_BUILD_CWL_TEST_RESOURCES}"

# Turn off call caching as hashing doesn't work since it sees local and not GCS paths.
# CWL conformance uses alpine images that do not have bash.
java \
    -Xmx2g \
    -Dconfig.file="${CROMWELL_BUILD_CROMWELL_CONFIG}" \
    -Dcall-caching.enabled=false \
    -Dsystem.job-shell=/bin/sh \
    -jar "${CROMWELL_BUILD_CROMWELL_JAR}" \
    server &

CROMWELL_PID=$!

sleep 30

java \
    -Xmx2g \
    -Dbackend.providers.Local.config.concurrent-job-limit="${CROMWELL_BUILD_CWL_TEST_PARALLELISM}" \
    -jar "${CROMWELL_BUILD_CROMWELL_JAR}" \
    run "${CROMWELL_BUILD_CWL_TEST_WDL}" \
    -i "${CROMWELL_BUILD_CWL_TEST_INPUTS}"

cd "${CROMWELL_BUILD_ROOT_DIRECTORY}"

cromwell::build::generate_code_coverage
