#!/bin/bash
# Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
set -e
prog javaserver 1 "tcp/$PORT_2" "$BINREF/runjava SimpleServer"
