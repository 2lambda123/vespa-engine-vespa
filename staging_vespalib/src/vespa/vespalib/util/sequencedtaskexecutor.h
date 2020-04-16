// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "isequencedtaskexecutor.h"
#include <vespa/vespalib/util/time.h>

namespace vespalib {

class SyncableThreadExecutor;

/**
 * Class to run multiple tasks in parallel, but tasks with same
 * id has to be run in sequence.
 */
class SequencedTaskExecutor final : public ISequencedTaskExecutor
{
    using Stats = vespalib::ExecutorStats;
    std::unique_ptr<std::vector<std::unique_ptr<vespalib::SyncableThreadExecutor>>> _executors;

    SequencedTaskExecutor(std::unique_ptr<std::vector<std::unique_ptr<vespalib::SyncableThreadExecutor>>> executor);
public:
    using ISequencedTaskExecutor::getExecutorId;
    using OptimizeFor = vespalib::Executor::OptimizeFor;

    ~SequencedTaskExecutor();

    void setTaskLimit(uint32_t taskLimit) override;
    void executeTask(ExecutorId id, vespalib::Executor::Task::UP task) override;
    void sync() override;
    Stats getStats() override;

    /*
     * Note that if you choose Optimize::THROUGHPUT, you must ensure only a single producer, or synchronize on the outside.
     *
     */
    static std::unique_ptr<ISequencedTaskExecutor>
    create(uint32_t threads, uint32_t taskLimit = 1000, OptimizeFor optimize = OptimizeFor::LATENCY, uint32_t kindOfWatermark = 0, duration reactionTime = 10ms);
};

} // namespace search
