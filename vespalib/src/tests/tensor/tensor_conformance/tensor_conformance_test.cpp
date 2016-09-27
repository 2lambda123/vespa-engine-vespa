// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/vespalib/eval/test/tensor_conformance.h>
#include <vespa/vespalib/eval/simple_tensor_engine.h>
#include <vespa/vespalib/tensor/default_tensor_engine.h>

using vespalib::eval::SimpleTensorEngine;
using vespalib::eval::test::TensorConformance;
using vespalib::tensor::DefaultTensorEngine;

TEST("require that reference tensor implementation passes all conformance tests") {
    TEST_DO(TensorConformance::run_tests(SimpleTensorEngine::ref(), true));
}

IGNORE_TEST("require that production tensor implementation passes non-mixed conformance tests") {
    TEST_DO(TensorConformance::run_tests(DefaultTensorEngine::ref(), false));
}

IGNORE_TEST("require that production tensor implementation passes all conformance tests") {
    TEST_DO(TensorConformance::run_tests(DefaultTensorEngine::ref(), true));
}

TEST_MAIN() { TEST_RUN_ALL(); }
