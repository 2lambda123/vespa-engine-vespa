#!/bin/bash -e
# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

usage() {
    echo "Usage: $0 <source-dir> [<extra-cmake-args>]" >&2
}

if [[ $# -eq 1 && ( "$1" = "-h" || "$1" = "--help" )]]; then
    usage
    exit 0
elif [[ $# -eq 1 ]]; then
    SOURCE_DIR=$1
    EXTRA_CMAKE_ARGS=""
elif [ $# -eq 2 ]; then
    SOURCE_DIR=$1
    EXTRA_CMAKE_ARGS=$2
else
    echo "Wrong number of arguments: expected 1 or 2, was $#" >&2
    usage
    exit 1
fi

if [ -z "$VESPA_LLVM_VERSION" ]; then
    VESPA_LLVM_VERSION=5.0
fi

cmake3 \
    -DCMAKE_INSTALL_PREFIX=$HOME/vespa \
    -DJAVA_HOME=/usr/lib/jvm/java-openjdk \
    -DEXTRA_LINK_DIRECTORY="$HOME/vespa-gtest/lib;/opt/vespa-boost/lib;/opt/vespa-cppunit/lib;/usr/lib64/llvm$VESPA_LLVM_VERSION/lib" \
    -DEXTRA_INCLUDE_DIRECTORY="/opt/vespa-gtest/include;/opt/vespa-boost/include;/opt/vespa-cppunit/include;/usr/include/llvm$VESPA_LLVM_VERSION" \
    -DCMAKE_INSTALL_RPATH="$HOME/vespa/lib64;/opt/vespa-gtest/lib;/opt/vespa-boost/lib;/opt/vespa-cppunit/lib;/usr/lib/jvm/java-1.8.0/jre/lib/amd64/server;/usr/lib64/llvm$VESPA_LLVM_VERSION/lib" \
    ${EXTRA_CMAKE_ARGS} \
    -DVESPA_LLVM_VERSION=$VESPA_LLVM_VERSION \
    -DVESPA_USER=$(id -un) \
    -DVESPA_UNPRIVILEGED=yes \
    "${SOURCE_DIR}"
