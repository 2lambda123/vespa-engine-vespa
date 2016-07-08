// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "isequencedtaskexecutor.h"
#include <vespa/vespalib/stllike/hash_map.h>
#include <vespa/vespalib/util/blockingthreadstackexecutor.h>

namespace vespalib
{

class ThreadStackExecutorBase;

}

namespace search
{

/**
 * Class to run multiple tasks in parallel, but tasks with same
 * id has to be run in sequence.
 */
class SequencedTaskExecutor : public ISequencedTaskExecutor
{
    std::vector<std::shared_ptr<vespalib::BlockingThreadStackExecutor>> _executors;
    vespalib::hash_map<size_t, size_t> _ids;
public:
    SequencedTaskExecutor(uint32_t threads, uint32_t taskLimit = 1000);

    ~SequencedTaskExecutor();

    void setTaskLimit(uint32_t taskLimit);

    virtual void executeTask(uint64_t id,
                             vespalib::Executor::Task::UP task) override;

    virtual void sync() override;
};

} // namespace search
