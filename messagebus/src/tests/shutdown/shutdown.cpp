// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/messagebus/emptyreply.h>
#include <vespa/messagebus/routing/retrytransienterrorspolicy.h>
#include <vespa/messagebus/testlib/receptor.h>
#include <vespa/messagebus/testlib/simplemessage.h>
#include <vespa/messagebus/testlib/simpleprotocol.h>
#include <vespa/messagebus/testlib/slobrok.h>
#include <vespa/messagebus/testlib/testserver.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/util/exceptions.h>

using namespace mbus;

class Test : public vespalib::TestApp {
private:
    void requireThatListenFailedIsExceptionSafe();
    void requireThatShutdownOnSourceWithPendingIsSafe();
    void requireThatShutdownOnIntermediateWithPendingIsSafe();

public:
    int Main() override {
        TEST_INIT("shutdown_test");

        requireThatListenFailedIsExceptionSafe();             TEST_FLUSH();
        requireThatShutdownOnSourceWithPendingIsSafe();       TEST_FLUSH();
        requireThatShutdownOnIntermediateWithPendingIsSafe(); TEST_FLUSH();

        TEST_DONE();
    }
};

static const double TIMEOUT = 120;

TEST_APPHOOK(Test);

void
Test::requireThatListenFailedIsExceptionSafe()
{
    FRT_Supervisor orb;
    ASSERT_TRUE(orb.Listen(0));
    ASSERT_TRUE(orb.Start());

    Slobrok slobrok;
    try {
        TestServer bar(MessageBusParams(),
                       RPCNetworkParams()
                       .setListenPort(orb.GetListenPort())
                       .setSlobrokConfig(slobrok.config()));
        EXPECT_TRUE(false);
    } catch (vespalib::Exception &e) {
        EXPECT_EQUAL("Failed to start network.",
                   e.getMessage());
    }
    orb.ShutDown(true);
}

void
Test::requireThatShutdownOnSourceWithPendingIsSafe()
{
    Slobrok slobrok;
    TestServer dstServer(MessageBusParams()
                         .addProtocol(IProtocol::SP(new SimpleProtocol())),
                         RPCNetworkParams()
                         .setIdentity(Identity("dst"))
                         .setSlobrokConfig(slobrok.config()));
    Receptor dstHandler;
    DestinationSession::UP dstSession = dstServer.mb.createDestinationSession(
            DestinationSessionParams()
            .setName("session")
            .setMessageHandler(dstHandler));
    ASSERT_TRUE(dstSession.get() != NULL);

    for (uint32_t i = 0; i < 10; ++i) {
        Message::UP msg(new SimpleMessage("msg"));
        {
            TestServer srcServer(MessageBusParams()
                    .setRetryPolicy(IRetryPolicy::SP(new RetryTransientErrorsPolicy()))
                    .addProtocol(IProtocol::SP(new SimpleProtocol())),
                    RPCNetworkParams()
                    .setSlobrokConfig(slobrok.config()));
            Receptor srcHandler;
            SourceSession::UP srcSession = srcServer.mb.createSourceSession(SourceSessionParams()
                    .setThrottlePolicy(IThrottlePolicy::SP())
                    .setReplyHandler(srcHandler));
            ASSERT_TRUE(srcSession.get() != NULL);
            ASSERT_TRUE(srcServer.waitSlobrok("dst/session", 1));
            ASSERT_TRUE(srcSession->send(std::move(msg), "dst/session", true).isAccepted());
            msg = dstHandler.getMessage(TIMEOUT);
            ASSERT_TRUE(msg.get() != NULL);
        }
        dstSession->acknowledge(std::move(msg));
    }
}

void
Test::requireThatShutdownOnIntermediateWithPendingIsSafe()
{
    Slobrok slobrok;
    TestServer dstServer(MessageBusParams()
                         .addProtocol(IProtocol::SP(new SimpleProtocol())),
                         RPCNetworkParams()
                         .setIdentity(Identity("dst"))
                         .setSlobrokConfig(slobrok.config()));
    Receptor dstHandler;
    DestinationSession::UP dstSession = dstServer.mb.createDestinationSession(
            DestinationSessionParams()
            .setName("session")
            .setMessageHandler(dstHandler));
    ASSERT_TRUE(dstSession.get() != NULL);

    TestServer srcServer(MessageBusParams()
                         .setRetryPolicy(IRetryPolicy::SP())
                         .addProtocol(IProtocol::SP(new SimpleProtocol())),
                         RPCNetworkParams()
                         .setSlobrokConfig(slobrok.config()));
    Receptor srcHandler;
    SourceSession::UP srcSession = srcServer.mb.createSourceSession(SourceSessionParams()
            .setThrottlePolicy(IThrottlePolicy::SP())
            .setReplyHandler(srcHandler));
    ASSERT_TRUE(srcSession.get() != NULL);
    ASSERT_TRUE(srcServer.waitSlobrok("dst/session", 1));

    for (uint32_t i = 0; i < 10; ++i) {
        Message::UP msg(new SimpleMessage("msg"));
        {
            TestServer itrServer(MessageBusParams()
                    .setRetryPolicy(IRetryPolicy::SP(new RetryTransientErrorsPolicy()))
                    .addProtocol(IProtocol::SP(new SimpleProtocol())),
                    RPCNetworkParams()
                    .setIdentity(Identity("itr"))
                    .setSlobrokConfig(slobrok.config()));
            Receptor itrHandler;
            IntermediateSession::UP itrSession = itrServer.mb.createIntermediateSession(
                    IntermediateSessionParams()
                    .setName("session")
                    .setMessageHandler(itrHandler)
                    .setReplyHandler(itrHandler));
            ASSERT_TRUE(itrSession.get() != NULL);
            ASSERT_TRUE(srcServer.waitSlobrok("itr/session", 1));
            ASSERT_TRUE(srcSession->send(std::move(msg), "itr/session dst/session", true).isAccepted());
            msg = itrHandler.getMessage(TIMEOUT);
            ASSERT_TRUE(msg.get() != NULL);
            itrSession->forward(std::move(msg));
            msg = dstHandler.getMessage(TIMEOUT);
            ASSERT_TRUE(msg.get() != NULL);
        }
        ASSERT_TRUE(srcServer.waitSlobrok("itr/session", 0));
        dstSession->acknowledge(std::move(msg));
        dstServer.mb.sync();
    }
}
