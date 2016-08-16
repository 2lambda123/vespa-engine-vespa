// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/vespalib/testkit/test_kit.h>

using namespace vespalib;

TEST_MAIN() {
    system("./vespalib_state_test_app > out.txt 2>&1 out.txt");
    system("cat out.txt | grep STATE | sed 's/([^)].*\\//(/' > actual.txt");

    const std::string src_dir = getenv("SOURCE_DIRECTORY") ? getenv("SOURCE_DIRECTORY") : ".";
    std::string diff_cmd("diff -u actual.txt ");
    diff_cmd += src_dir + "/expect.txt";
    EXPECT_EQUAL(system(diff_cmd.c_str()), 0);
}
