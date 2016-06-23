#!/bin/bash
set -e
IINSPECT=../../../apps/vespa-index-inspect/searchlib_vespa-index-inspect_app 
ECHO_CMD=echo

$VALGRIND ./searchlib_fusion_test_app
$ECHO_CMD showing usage
$IINSPECT --help > usage.out 2>&1 || true
$ECHO_CMD dumping dictionary words for field f0
$IINSPECT dumpwords --indexdir dump3 --field f0 > dumpwords.out
$ECHO_CMD transposing index back for inspection
$IINSPECT showpostings --transpose --indexdir dump3 > transpose.out
$ECHO_CMD dumping posting list for word z in field f0
$IINSPECT showpostings --indexdir dump3 --field f0 z > zwordf0field.out
$ECHO_CMD inspection done.
