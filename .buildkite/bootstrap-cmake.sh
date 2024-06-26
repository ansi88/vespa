#!/bin/bash

set -euo pipefail

source /etc/profile.d/enable-gcc-toolset.sh

VESPA_CMAKE_SANITIZERS_OPTION=""
VESPA_CMAKE_CCACHE_OPTION=""
if [[ $VESPA_USE_SANITIZER != null ]]; then
    VESPA_CMAKE_SANITIZERS_OPTION="-DVESPA_USE_SANITIZER=$VESPA_USE_SANITIZER"
    VESPA_CMAKE_CCACHE_OPTION="-DVESPA_USE_CCACHE=false"
    VALGRIND_UNIT_TESTS=false
fi
if [[ $BUILDKITE_PULL_REQUEST != "false" ]]; then
    VALGRIND_UNIT_TESTS=false
fi

cmake3 -DVESPA_UNPRIVILEGED=no -DVALGRIND_UNIT_TESTS="$VALGRIND_UNIT_TESTS" \
  "$VESPA_CMAKE_SANITIZERS_OPTION" "$VESPA_CMAKE_CCACHE_OPTION" "$SOURCE_DIR"
