#!/bin/bash
set -e
. ../../binref/env.sh

$BINREF/compilejava CasingVariants.java
bash -e dotest.sh
