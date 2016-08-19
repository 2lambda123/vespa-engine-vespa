// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/testkit/testapp.h>
#include <vbench/test/all.h>

using namespace vbench;

TEST("mapped file input") {
    {
        MappedFileInput file("not_found.txt");
        EXPECT_TRUE(file.tainted());
    }
    {
        MappedFileInput file(vespalib::TestApp::GetSourceDirectory() + "file.txt");
        EXPECT_TRUE(!file.tainted());
        LineReader reader(file, 3);
        string line;
        EXPECT_TRUE(reader.readLine(line));
        EXPECT_EQUAL("file content", line);
        EXPECT_TRUE(!reader.readLine(line));
    }
}

TEST_MAIN() { TEST_RUN_ALL(); }
