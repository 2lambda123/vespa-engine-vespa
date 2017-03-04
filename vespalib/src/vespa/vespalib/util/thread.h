// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "sync.h"
#include "runnable.h"
#include "active.h"
#include <vespa/fastos/thread.h>

namespace vespalib {

/**
 * Abstraction of the concept of running a single thread.
 **/
class Thread : public Active
{
private:
    enum { STACK_SIZE = 256*1024 };
    static __thread Thread *_currentThread;

    struct Proxy : FastOS_Runnable {
        Thread         &thread;
        Runnable       &runnable;
        vespalib::Gate  start;
        vespalib::Gate  started;
        bool            cancel;

        Proxy(Thread &parent, Runnable &target)
            : thread(parent), runnable(target),
              start(), started(), cancel(false) { }
        ~Proxy();

        void Run(FastOS_ThreadInterface *thisThread, void *arguments) override;
    };

    Proxy             _proxy;
    FastOS_ThreadPool _pool;
    vespalib::Monitor _monitor;
    bool              _stopped;
    bool              _woken;

public:
    Thread(Runnable &runnable);
    ~Thread();
    virtual void start();
    virtual Thread &stop();
    virtual void join();
    bool stopped() const { return _stopped; }
    bool slumber(double s);
    static Thread &currentThread();
    static void sleep(size_t ms);
};

} // namespace vespalib

