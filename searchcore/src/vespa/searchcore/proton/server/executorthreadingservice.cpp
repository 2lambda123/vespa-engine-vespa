// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.server.executorthreadingservice");

#include "executorthreadingservice.h"
#include <vespa/vespalib/util/executor.h>
#include <vespa/vespalib/util/sync.h>

using vespalib::ThreadStackExecutorBase;

namespace proton {

ExecutorThreadingService::ExecutorThreadingService(uint32_t threads,
                                                   uint32_t stackSize,
                                                   uint32_t taskLimit)

    : _masterExecutor(1, stackSize),
      _indexExecutor(1, stackSize, taskLimit),
      _masterService(_masterExecutor),
      _indexService(_indexExecutor),
      _indexFieldInverter(threads, taskLimit),
      _indexFieldWriter(threads, taskLimit),
      _attributeFieldWriter(threads, taskLimit)
{
}

vespalib::Syncable &
ExecutorThreadingService::sync()
{
    bool isMasterThread = _masterService.isCurrentThread();
    if (!isMasterThread) {
        _masterExecutor.sync();
    }
    _attributeFieldWriter.sync();
    _indexExecutor.sync();
    _indexFieldInverter.sync();
    _indexFieldWriter.sync();
    if (!isMasterThread) {
        _masterExecutor.sync();
    }
    return *this;
}

void
ExecutorThreadingService::shutdown()
{
    _masterExecutor.shutdown();
    _masterExecutor.sync();
    _attributeFieldWriter.sync();
    _indexExecutor.shutdown();
    _indexExecutor.sync();
    _indexFieldInverter.sync();
    _indexFieldWriter.sync();
}

void
ExecutorThreadingService::setTaskLimit(uint32_t taskLimit)
{
    _indexExecutor.setTaskLimit(taskLimit);
    _indexFieldInverter.setTaskLimit(taskLimit);
    _indexFieldWriter.setTaskLimit(taskLimit);
    _attributeFieldWriter.setTaskLimit(taskLimit);
}

void
ExecutorThreadingService::setUnboundTaskLimit()
{
    setTaskLimit(std::numeric_limits<uint32_t>::max());
}

} // namespace proton

