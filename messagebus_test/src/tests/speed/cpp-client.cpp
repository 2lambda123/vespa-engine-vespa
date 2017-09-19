// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/messagebus/messagebus.h>
#include <vespa/messagebus/routing/retrytransienterrorspolicy.h>
#include <vespa/messagebus/rpcmessagebus.h>
#include <vespa/messagebus/network/rpcnetworkparams.h>
#include <vespa/messagebus/testlib/simplemessage.h>
#include <vespa/messagebus/testlib/simpleprotocol.h>
#include <vespa/messagebus/testlib/simplereply.h>
#include <vespa/fastos/app.h>

using namespace mbus;

class Client : public IReplyHandler
{
private:
    vespalib::Lock    _lock;
    uint32_t          _okCnt;
    uint32_t          _failCnt;
    SourceSession::UP _session;
    static uint64_t   _seq;
public:
    Client(MessageBus &bus, const SourceSessionParams &params);
    ~Client();
    void send();
    void send(uint64_t seq);
    void sample(uint32_t &okCnt, uint32_t &failCnt);
    void handleReply(Reply::UP reply) override;
};
uint64_t Client::_seq = 100000;

Client::Client(MessageBus &bus, const SourceSessionParams &params)
    : _lock(),
      _okCnt(0),
      _failCnt(0),
      _session(bus.createSourceSession(*this, params))
{
}

Client::~Client()
{
    _session->close();
}

void
Client::send() {
    send(++_seq);
}

void
Client::send(uint64_t seq) {
    Message::UP msg(new SimpleMessage("message", true, seq));
    _session->send(std::move(msg), "test");
}

void
Client::sample(uint32_t &okCnt, uint32_t &failCnt) {
    vespalib::LockGuard guard(_lock);
    okCnt = _okCnt;
    failCnt = _failCnt;
}

void
Client::handleReply(Reply::UP reply) {
    if ((reply->getProtocol() == SimpleProtocol::NAME)
        && (reply->getType() == SimpleProtocol::REPLY)
        && (static_cast<SimpleReply&>(*reply).getValue() == "OK"))
    {
        vespalib::LockGuard guard(_lock);
        ++_okCnt;
    } else {
        fprintf(stderr, "BAD REPLY\n");
        for (uint32_t i = 0; i < reply->getNumErrors(); ++i) {
            fprintf(stderr, "ERR[%d]: code=%d, msg=%s\n", i,
                    reply->getError(i).getCode(),
                    reply->getError(i).getMessage().c_str());
        }
        vespalib::LockGuard guard(_lock);
        ++_failCnt;
    }
    send();
}

class App : public FastOS_Application
{
public:
    int Main() override;
};

int
App::Main()
{
    RetryTransientErrorsPolicy::SP retryPolicy(new RetryTransientErrorsPolicy());
    retryPolicy->setBaseDelay(0.1);
    RPCMessageBus mb(MessageBusParams().setRetryPolicy(retryPolicy).addProtocol(IProtocol::SP(new SimpleProtocol())),
                     RPCNetworkParams().setIdentity(Identity("server/cpp")).setSlobrokConfig("file:slobrok.cfg"),
                     "file:routing.cfg");
    Client client(mb.getMessageBus(), SourceSessionParams().setTimeout(30));

    // let the system 'warm up'
    FastOS_Thread::Sleep(5000);

    // inject messages into the feedback loop
    for (uint32_t i = 0; i < 1024; ++i) {
        client.send(i);
    }

    // let the system 'warm up'
    FastOS_Thread::Sleep(5000);

    FastOS_Time start;
    FastOS_Time stop;
    uint32_t okBefore   = 0;
    uint32_t okAfter    = 0;
    uint32_t failBefore = 0;
    uint32_t failAfter  = 0;

    start.SetNow();
    client.sample(okBefore, failBefore);
    FastOS_Thread::Sleep(10000); // Benchmark time
    stop.SetNow();
    client.sample(okAfter, failAfter);
    stop -= start;
    double time = stop.MilliSecs();
    double msgCnt = (double)(okAfter - okBefore);
    double throughput = (msgCnt / time) * 1000.0;
    fprintf(stdout, "CPP-CLIENT: %g msg/s\n", throughput);
    if (failAfter > failBefore) {
        fprintf(stderr, "CPP-CLIENT: FAILED (%d -> %d)\n", failBefore, failAfter);
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    fprintf(stderr, "started '%s'\n", argv[0]);
    fflush(stderr);
    App app;
    int r = app.Entry(argc, argv);
    fprintf(stderr, "stopping '%s'\n", argv[0]);
    fflush(stderr);
    return r;
}
