// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright (C) 2010 Yahoo

#pragma once

#include "threadexecutor.h"
#include "eventbarrier.hpp"
#include "arrayqueue.hpp"
#include "sync.h"
#include <memory>
#include "noncopyable.hpp"

namespace vespalib {

/**
 * An executor service that executes tasks in multiple threads.
 **/
class ThreadStackExecutorBase : public ThreadExecutor,
                                public FastOS_Runnable,
                                public noncopyable
{
public:
    /**
     * Internal stats that we want to observe externally. Note that
     * all stats are reset each time they are observed.
     **/
    struct Stats {
        size_t maxPendingTasks;
        size_t acceptedTasks;
        size_t rejectedTasks;
        Stats() : maxPendingTasks(0), acceptedTasks(0), rejectedTasks(0) {}
    };

private:
    struct TaggedTask {
        Task     *task;
        uint32_t  token;
        TaggedTask() : task(0), token(0) {}
        TaggedTask(Task *task_in, uint32_t token_in)
            : task(task_in), token(token_in) {}
    };

    struct Worker {
        Monitor    monitor;
        bool       idle;
        TaggedTask task;
        Worker() : monitor(), idle(false), task() {}
    };

    struct BarrierCompletion {
        Gate gate;
        void completeBarrier() { gate.countDown(); }
    };

    struct BlockedThread {
        const uint32_t wait_task_count;
        Monitor monitor;
        bool blocked;
        BlockedThread(uint32_t wait_task_count_in)
            : wait_task_count(wait_task_count_in), monitor(), blocked(true) {}
        void wait() const;
        void unblock();
    };

    FastOS_ThreadPool               _pool;
    Monitor                         _monitor;
    Stats                           _stats;
    Gate                            _executorCompletion;
    ArrayQueue<TaggedTask>          _tasks;
    ArrayQueue<Worker*>             _workers;
    std::vector<BlockedThread*>     _blocked;
    EventBarrier<BarrierCompletion> _barrier;
    uint32_t                        _taskCount;
    uint32_t                        _taskLimit;
    bool                            _closed;

    void block_thread(const LockGuard &, BlockedThread &blocked_thread);
    void unblock_threads(const MonitorGuard &);

    /**
     * Assign the given task to the given idle worker. This will wake
     * up a worker thread that is blocked in the obtainTask function.
     *
     * @param task the task to assign
     * @param worker an idle worker
     **/
    void assignTask(const TaggedTask &task, Worker &worker);

    /**
     * Obtain a new task to be run by the given worker.  This function
     * will block until a task is obtained or the executor is shut
     * down.
     *
     * @return true if a task was obtained, false if we are done
     * @param worker the worker looking for work
     **/
    bool obtainTask(Worker &worker);

    // from FastOS_Runnable (all workers live here)
    virtual void Run(FastOS_ThreadInterface *, void *);

protected:
    /**
     * This will tell if a task will be accepted or not.
     * An implementation might decide to block.
     */
    virtual bool acceptNewTask(MonitorGuard & monitor) = 0;

    /**
     * If blocking implementation, this might wake up any waiters.
     *
     * @param monitor to use for signaling.
     */
    virtual void wakeup(MonitorGuard & monitor) = 0;

    /**
     *  Will tell you if the executor has been closed for new tasks.
     */
    bool closed() const { return _closed; }

    /**
     * This will cleanup before destruction. All implementations must call this
     * in destructor.
     */
    void cleanup();

    /**
     *  Will tell if there is room for a new task in the Q.
     */
    bool isRoomForNewTask() const { return (_taskCount < _taskLimit); }

    /**
     * Create a new thread stack executor. The task limit specifies
     * the maximum number of tasks that are currently handled by this
     * executor. Both the number of threads and the task limit must be
     * greater than 0.
     *
     * @param stackSize stack size per worker thread
     * @param taskLimit upper limit on accepted tasks
     **/
    ThreadStackExecutorBase(uint32_t stackSize, uint32_t taskLimit);

    /**
     * This will start the theads. This is to avoid starting tasks in
     * constructor of base class.
     *
     * @param threads number of worker threads (concurrent tasks)
     */
    void start(uint32_t threads);

    /**
     * Sets a new upper limit for accepted number of tasks.
     */
    void internalSetTaskLimit(uint32_t taskLimit);

public:
    /**
     * Observe and reset stats for this object.
     *
     * @return stats
     **/
    Stats getStats();

    // inherited from Executor
    virtual Task::UP execute(Task::UP task);

    /**
     * Synchronize with this executor. This function will block until
     * all previously accepted tasks have been executed. This function
     * uses the event barrier algorithm (tm).
     *
     * @return this object; for chaining
     **/
    virtual ThreadStackExecutorBase &sync();

    /**
     * Block the calling thread until the current task count is equal
     * to or lower than the given value.
     *
     * @param task_count target value to wait for
     **/
    void wait_for_task_count(uint32_t task_count);

    /**
     * Shut down this executor. This will make this executor reject
     * all new tasks.
     *
     * @return this object; for chaining
     **/
    ThreadStackExecutorBase &shutdown();

    /**
     * Will invoke shutdown then sync.
     **/
    ~ThreadStackExecutorBase();
};

} // namespace vespalib

