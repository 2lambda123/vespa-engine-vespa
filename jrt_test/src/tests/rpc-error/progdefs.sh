#!/bin/bash
# Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
set -e
prog cppserver 1 "tcp/$CPP_PORT" "$BINREF/simpleserver"
prog javaserver 1 "tcp/$JAVA_PORT" "$BINREF/runjava SimpleServer"
