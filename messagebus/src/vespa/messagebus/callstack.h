// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vector>
#include "context.h"

namespace mbus {

class IDiscardHandler;
class IReplyHandler;
class Reply;

/**
 * A CallStack is used to ensure that a Reply travels the inverse path
 * of its Message. Each Routable has a CallStack used to track its
 * path. Each stack frame contains a pointer to an IReplyHandler and a
 * message Context for that handler. Note that a CallStack does not
 * own any objects. Also note that the CallStack object will not be
 * copied when copying a Routable, as it is not part of the object
 * value. This class is intended for internal messagebus use only.
 **/
class CallStack
{
private:
    struct Frame {
        IReplyHandler   *replyHandler;
        IDiscardHandler *discardHandler;
        Context          ctx;
    };

    typedef std::vector<Frame> Stack;

    Stack _stack;

public:
    CallStack(const CallStack &) = delete;
    CallStack & operator = (const CallStack &) = delete;
    /**
     * Create a new empty CallStack.
     **/
    CallStack() : _stack() { }

    /**
     * Swap the content of this and the argument stack.
     *
     * @param dst The stack to swap content with.
     **/
    void swap(CallStack &dst);

    /**
     * Discard this CallStack. This method should only be used when you are
     * certain that it is safe to just throw away the stack. It has similar
     * effects to stopping a thread, you need to know where it is safe to do so.
     **/
    void discard();

    /**
     * Obtain the number of frames currently on this stack.
     *
     * @return stack size in frames
     **/
    uint32_t size() const { return _stack.size(); }

    /**
     * Push a frame on this stack. The discard handler is an optional handler,
     * and may be null.
     *
     * @param replyHandler   The handler for the correponding reply.
     * @param ctx            The context to store.
     * @param discardHandler The handler for discarded messages.
     **/
    void push(IReplyHandler &replyHandler, Context ctx,
              IDiscardHandler *discardHandler = NULL);

    /**
     * Pop a frame from this stack. The handler part of the frame will
     * be returned and the context part will be set on the given
     * Reply. Invoke this method on an empty stack and terrible things
     * will happen.
     *
     * @return the next handler on the stack
     * @param reply Reply that will receive the next context
     **/
    IReplyHandler &pop(Reply &reply);
};

} // namespace mbus

