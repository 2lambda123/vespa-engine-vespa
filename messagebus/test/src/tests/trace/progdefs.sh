#!/bin/bash
# Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
set -e
prog server cpp1 "" "./messagebus_test_cpp-server-trace_app server/cpp/1/A"
prog server cpp2 "" "./messagebus_test_cpp-server-trace_app server/cpp/2/A"
prog server cpp3 "" "./messagebus_test_cpp-server-trace_app server/cpp/2/B"
prog server cpp4 "" "./messagebus_test_cpp-server-trace_app server/cpp/3/A"
prog server cpp5 "" "./messagebus_test_cpp-server-trace_app server/cpp/3/B"
prog server cpp6 "" "./messagebus_test_cpp-server-trace_app server/cpp/3/C"
prog server cpp7 "" "./messagebus_test_cpp-server-trace_app server/cpp/3/D"
prog server java1 "" "../../binref/runjava JavaServer server/java/1/A"
prog server java2 "" "../../binref/runjava JavaServer server/java/2/A"
prog server java3 "" "../../binref/runjava JavaServer server/java/2/B"
prog server java4 "" "../../binref/runjava JavaServer server/java/3/A"
prog server java5 "" "../../binref/runjava JavaServer server/java/3/B"
prog server java6 "" "../../binref/runjava JavaServer server/java/3/C"
prog server java7 "" "../../binref/runjava JavaServer server/java/3/D"
