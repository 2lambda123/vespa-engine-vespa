// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/config/config.h>
#include <vespa/config/common/configholder.h>
#include <vespa/config/file/filesource.h>
#include <vespa/vespalib/util/sync.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <fstream>
#include <config-my.h>
#include <config-foo.h>
#include <config-foodefault.h>
#include <config-bar.h>
#include <config-foobar.h>
#include <vespa/log/log.h>
LOG_SETUP(".filesubscription_test");

using namespace config;

namespace {

    void writeFile(const std::string & fileName, const std::string & myFieldVal)
    {
        std::ofstream of;
        of.open(fileName.c_str());
        of << "myField \"" << myFieldVal << "\"\n";
        of.close();
    }
}


TEST("requireThatFileSpecGivesCorrectKey") {
    std::string str("/home/my/config.cfg");
    FileSpec spec(str);
    bool thrown = false;
    try {
        FileSpec s1("fb");
        FileSpec s2("fb.cfh");
        FileSpec s3("fb.dch");
        FileSpec s4("fbcfg");
        FileSpec s5(".cfg");
    } catch (const InvalidConfigSourceException & e) {
        thrown = true;
    }
    ASSERT_TRUE(thrown);

    thrown = false;
    try {
        FileSpec s1("fb.cfg");
        FileSpec s2("a.cfg");
        FileSpec s3("fljdlfjsalf.cfg");
    } catch (const InvalidConfigSourceException & e) {
        thrown = true;
    }
    ASSERT_FALSE(thrown);
}

TEST("requireThatFileSpecGivesCorrectSource") {
    writeFile("my.cfg", "foobar");
    FileSpec spec("my.cfg");

    SourceFactory::UP factory(spec.createSourceFactory(TimingValues()));
    ASSERT_TRUE(factory.get() != NULL);
    IConfigHolder::SP holder(new ConfigHolder());
    Source::UP src = factory->createSource(holder, ConfigKey("my", "my", "bar", "foo"));
    ASSERT_TRUE(src.get() != NULL);

    src->getConfig();
    ASSERT_TRUE(holder->poll());
    ConfigUpdate::UP update(holder->provide());
    ASSERT_TRUE(update.get() != NULL);
    const ConfigValue & value(update->getValue());
    ASSERT_EQUAL(1u, value.numLines());
    ASSERT_EQUAL("myField \"foobar\"", value.getLine(0));
}

TEST("requireThatFileSubscriptionReturnsCorrectConfig") {
    writeFile("my.cfg", "foobar");
    ConfigSubscriber s(FileSpec("my.cfg"));
    std::unique_ptr<ConfigHandle<MyConfig> > handle = s.subscribe<MyConfig>("my");
    s.nextConfig(0);
    std::unique_ptr<MyConfig> cfg = handle->getConfig();
    ASSERT_TRUE(cfg.get() != NULL);
    ASSERT_EQUAL("foobar", cfg->myField);
    ASSERT_EQUAL("my", cfg->defName());
    ASSERT_FALSE(s.nextConfig(100));
}

TEST("requireThatReconfigIsCalledWhenConfigChanges") {
    writeFile("my.cfg", "foo");
    {
        IConfigContext::SP context(new ConfigContext(FileSpec("my.cfg")));
        ConfigSubscriber s(context);
        std::unique_ptr<ConfigHandle<MyConfig> > handle = s.subscribe<MyConfig>("");
        s.nextConfig(0);
        std::unique_ptr<MyConfig> cfg = handle->getConfig();
        ASSERT_TRUE(cfg.get() != NULL);
        ASSERT_EQUAL("foo", cfg->myField);
        ASSERT_EQUAL("my", cfg->defName());
        ASSERT_FALSE(s.nextConfig(3000));
        writeFile("my.cfg", "bar");
        context->reload();
        bool correctValue = false;
        FastOS_Time timer;
        timer.SetNow();
        while (!correctValue && timer.MilliSecsToNow() < 20000.0) {
            LOG(info, "Testing value...");
            if (s.nextConfig(1000)) {
                break;
            }
        }
        cfg = handle->getConfig();
        ASSERT_TRUE(cfg.get() != NULL);
        ASSERT_EQUAL("bar", cfg->myField);
        ASSERT_EQUAL("my", cfg->defName());
        ASSERT_FALSE(s.nextConfig(1000));
    }
}

TEST("requireThatMultipleSubscribersCanSubscribeToSameFile") {
    writeFile("my.cfg", "foobar");
    FileSpec spec("my.cfg");
    {
        ConfigSubscriber s1(spec);
        std::unique_ptr<ConfigHandle<MyConfig> > h1 = s1.subscribe<MyConfig>("");
        ASSERT_TRUE(s1.nextConfig(0));
        ConfigSubscriber s2(spec);
        std::unique_ptr<ConfigHandle<MyConfig> > h2 = s2.subscribe<MyConfig>("");
        ASSERT_TRUE(s2.nextConfig(0));
    }
}

TEST("requireThatCanSubscribeToDirectory") {
    DirSpec spec(vespalib::TestApp::GetSourceDirectory() + "cfgdir");
    ConfigSubscriber s(spec);
    ConfigHandle<FooConfig>::UP fooHandle = s.subscribe<FooConfig>("");
    ConfigHandle<BarConfig>::UP barHandle = s.subscribe<BarConfig>("");
    ASSERT_TRUE(s.nextConfig(0));
    ASSERT_TRUE(fooHandle->isChanged());
    ASSERT_TRUE(barHandle->isChanged());
    std::unique_ptr<FooConfig> fooCfg = fooHandle->getConfig();
    std::unique_ptr<BarConfig> barCfg = barHandle->getConfig();
    ASSERT_TRUE(fooCfg.get() != NULL);
    ASSERT_TRUE(barCfg.get() != NULL);
    ASSERT_EQUAL("foofoo", fooCfg->fooValue);
    ASSERT_EQUAL("barbar", barCfg->barValue);
}

TEST("requireThatCanSubscribeToDirectoryWithEmptyCfgFile") {
    DirSpec spec(vespalib::TestApp::GetSourceDirectory() + "cfgemptyfile");
    ConfigSubscriber s(spec);
    ConfigHandle<FoodefaultConfig>::UP fooHandle = s.subscribe<FoodefaultConfig>("");
    ConfigHandle<BarConfig>::UP barHandle = s.subscribe<BarConfig>("");
    ASSERT_TRUE(s.nextConfig(0));
    ASSERT_TRUE(fooHandle->isChanged());
    ASSERT_TRUE(barHandle->isChanged());
    std::unique_ptr<FoodefaultConfig> fooCfg = fooHandle->getConfig();
    std::unique_ptr<BarConfig> barCfg = barHandle->getConfig();
    ASSERT_TRUE(fooCfg.get() != NULL);
    ASSERT_TRUE(barCfg.get() != NULL);
    ASSERT_EQUAL("per", fooCfg->fooValue);
    ASSERT_EQUAL("barbar", barCfg->barValue);
}

TEST("requireThatCanSubscribeToDirectoryWithNonExistingCfgFile") {
    DirSpec spec(vespalib::TestApp::GetSourceDirectory() + "cfgnonexistingfile");
    ConfigSubscriber s(spec);
    ConfigHandle<FoodefaultConfig>::UP fooHandle = s.subscribe<FoodefaultConfig>("");
    ConfigHandle<BarConfig>::UP barHandle = s.subscribe<BarConfig>("");
    ASSERT_TRUE(s.nextConfig(0));
    ASSERT_TRUE(fooHandle->isChanged());
    ASSERT_TRUE(barHandle->isChanged());
    std::unique_ptr<FoodefaultConfig> fooCfg = fooHandle->getConfig();
    std::unique_ptr<BarConfig> barCfg = barHandle->getConfig();
    ASSERT_TRUE(fooCfg.get() != NULL);
    ASSERT_TRUE(barCfg.get() != NULL);
    ASSERT_EQUAL("per", fooCfg->fooValue);
    ASSERT_EQUAL("barbar", barCfg->barValue);
}

TEST_F("requireThatDirSpecDoesNotMixNames",
       DirSpec(vespalib::TestApp::GetSourceDirectory() + "cfgdir2")) {
    ConfigSubscriber s(f);
    ConfigHandle<BarConfig>::UP barHandle = s.subscribe<BarConfig>("");
    ConfigHandle<FoobarConfig>::UP foobarHandle = s.subscribe<FoobarConfig>("");
    s.nextConfig(0);
    std::unique_ptr<BarConfig> bar = barHandle->getConfig();
    std::unique_ptr<FoobarConfig> foobar = foobarHandle->getConfig();
    ASSERT_TRUE(bar.get() != NULL);
    ASSERT_TRUE(foobar.get() != NULL);
    ASSERT_EQUAL("barbar", bar->barValue);
    ASSERT_EQUAL("foobarlol", foobar->fooBarValue);
}

TEST_F("require that can subscribe multiple config ids of same config",
       DirSpec(vespalib::TestApp::GetSourceDirectory() + "cfgdir3")) {
    ConfigSubscriber s(f1);
    ConfigHandle<BarConfig>::UP fooHandle = s.subscribe<BarConfig>("foo");
    ConfigHandle<BarConfig>::UP barHandle = s.subscribe<BarConfig>("bar");
    s.nextConfig(0);
    std::unique_ptr<BarConfig> bar1 = fooHandle->getConfig();
    std::unique_ptr<BarConfig> bar2 = barHandle->getConfig();
    ASSERT_TRUE(bar1.get() != NULL);
    ASSERT_TRUE(bar2.get() != NULL);
    ASSERT_EQUAL("barbar", bar1->barValue);
    ASSERT_EQUAL("foobarlol", bar2->barValue);
}

TEST_MAIN() { TEST_RUN_ALL(); }
