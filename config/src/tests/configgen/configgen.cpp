// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("configgen");
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/config/config.h>
#include "config-motd.h"

using namespace config;

TEST("require that config type can be compiled") {
    const std::string src_dir = getenv("SOURCE_DIRECTORY") ? getenv("SOURCE_DIRECTORY") : ".";

    std::unique_ptr<MotdConfig> cfg = ConfigGetter<MotdConfig>::getConfig("motd", FileSpec(src_dir + "/motd.cfg"));
}

TEST_MAIN() { TEST_RUN_ALL(); }
