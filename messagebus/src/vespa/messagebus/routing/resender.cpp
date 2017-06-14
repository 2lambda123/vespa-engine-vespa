// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "resender.h"
#include "routingnode.h"
#include <vespa/messagebus/error.h>
#include <vespa/messagebus/errorcode.h>
#include <vespa/messagebus/tracelevel.h>
#include <vespa/vespalib/util/stringfmt.h>

namespace mbus {

Resender::Resender(IRetryPolicy::SP retryPolicy) :
    _queue(),
    _retryPolicy(retryPolicy),
    _time()
{
    _time.SetNow();
}

Resender::~Resender()
{
    while (!_queue.empty()) {
        _queue.top().second->discard();
        _queue.pop();
    }
}

void
Resender::resendScheduled()
{
    typedef std::vector<RoutingNode*> NodeList;
    NodeList sendList;

    double now = _time.MilliSecsToNow();
    while (!_queue.empty() && _queue.top().first <= now) {
        sendList.push_back(_queue.top().second);
        _queue.pop();
    }

    for (NodeList::iterator it = sendList.begin();
         it != sendList.end(); ++it)
    {
        (*it)->getTrace().trace(mbus::TraceLevel::COMPONENT,
                                "Resender resending message.");
        (*it)->send();
    }
}

bool
Resender::canRetry(uint32_t errorCode) const
{
    return _retryPolicy->canRetry(errorCode);
}

bool
Resender::shouldRetry(const Reply &reply) const
{
    uint32_t numErrors = reply.getNumErrors();
    if (numErrors == 0) {
        return false;
    }
    for (uint32_t i = 0; i < numErrors; ++i) {
        if (!_retryPolicy->canRetry(reply.getError(i).getCode())) {
            return false;
        }
    }
    return true;
}

bool
Resender::scheduleRetry(RoutingNode &node)
{
    Message &msg = node.getMessage();
    if (!msg.getRetryEnabled()) {
        return false;
    }
    uint32_t retry = msg.getRetry() + 1;
    double delay = node.getReplyRef().getRetryDelay();
    if (delay < 0) {
        delay = _retryPolicy->getRetryDelay(retry);
    }
    if (msg.getTimeRemainingNow() * 0.001 - delay <= 0) {
        node.addError(ErrorCode::TIMEOUT, "Timeout exceeded by resender, giving up.");
        return false;
    }
    node.prepareForRetry(); // consumes the reply
    node.getTrace().trace(
        TraceLevel::COMPONENT,
        vespalib::make_string("Message scheduled for retry %u in %.2f seconds.", retry, delay));
    msg.setRetry(retry);
    _queue.push(Entry((uint64_t)(_time.MilliSecsToNow() + delay * 1000), &node));
    return true;
}

} // namespace mbus
