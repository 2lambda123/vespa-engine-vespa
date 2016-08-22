// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/config/config.h>
#include <vespa/config/raw/rawsource.h>
#include "config-my.h"

using namespace config;

namespace {

struct ConfigFixture {
    MyConfigBuilder builder;
    ConfigSet set;
    ConfigContext::SP context;
    ConfigFixture() : builder(), set(), context() {
        set.addBuilder("cfgid", &builder);
        context.reset(new ConfigContext(set));
    }
};

} // namespace <unnamed>

TEST("requireThatGetConfigReturnsCorrectConfig")
{
    RawSpec spec("myField \"foo\"\n");

    std::unique_ptr<MyConfig> cfg = ConfigGetter<MyConfig>::getConfig("myid", spec);
    ASSERT_TRUE(cfg.get() != NULL);
    ASSERT_EQUAL("my", cfg->defName());
    ASSERT_EQUAL("foo", cfg->myField);
}


TEST("requireThatGetConfigReturnsCorrectConfig")
{
    FileSpec spec(vespalib::TestApp::GetSourceDirectory() + "my.cfg");
    std::unique_ptr<MyConfig> cfg = ConfigGetter<MyConfig>::getConfig("", spec);
    ASSERT_TRUE(cfg.get() != NULL);
    ASSERT_EQUAL("my", cfg->defName());
    ASSERT_EQUAL("foobar", cfg->myField);
}

TEST_F("require that ConfigGetter can be used to obtain config generation", ConfigFixture) {
    f1.builder.myField = "foo";
    {
        int64_t gen1;
        int64_t gen2;
        std::unique_ptr<MyConfig> cfg1 = ConfigGetter<MyConfig>::getConfig(gen1, "cfgid", f1.set);
        std::unique_ptr<MyConfig> cfg2 = ConfigGetter<MyConfig>::getConfig(gen2, "cfgid", f1.context);
        EXPECT_EQUAL(1, gen1);
        EXPECT_EQUAL(1, gen2);
        EXPECT_EQUAL("foo", cfg1.get()->myField);
        EXPECT_EQUAL("foo", cfg2.get()->myField);
    }
    f1.builder.myField = "bar";
    f1.context->reload();
    {
        int64_t gen1;
        int64_t gen2;
        std::unique_ptr<MyConfig> cfg1 = ConfigGetter<MyConfig>::getConfig(gen1, "cfgid", f1.set);
        std::unique_ptr<MyConfig> cfg2 = ConfigGetter<MyConfig>::getConfig(gen2, "cfgid", f1.context);
        EXPECT_EQUAL(1, gen1); // <-- NB: generation will not increase when using the builder set directly
        EXPECT_EQUAL(2, gen2);
        EXPECT_EQUAL("bar", cfg1.get()->myField);
        EXPECT_EQUAL("bar", cfg2.get()->myField);
    }
}

TEST_MAIN() { TEST_RUN_ALL(); }
