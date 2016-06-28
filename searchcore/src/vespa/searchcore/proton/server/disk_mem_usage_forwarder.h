// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchcorespi/index/i_thread_service.h>
#include "i_disk_mem_usage_notifier.h"
#include "i_disk_mem_usage_listener.h"

namespace proton
{

/**
 * Forwarder for disk/memory usage state changes. Notification is forwarded
 * as a task run by the supplied executor.
 */
class DiskMemUsageForwarder : public IDiskMemUsageNotifier,
                              public IDiskMemUsageListener
{
    searchcorespi::index::IThreadService &_executor;
    std::vector<IDiskMemUsageListener *> _listeners;
    DiskMemUsageState _state;
    void forward(DiskMemUsageState state);
public:
    DiskMemUsageForwarder(searchcorespi::index::IThreadService &executor);
    virtual ~DiskMemUsageForwarder();
    virtual void addDiskMemUsageListener(IDiskMemUsageListener *listener) override;
    virtual void removeDiskMemUsageListener(IDiskMemUsageListener *listener) override;
    virtual void notifyDiskMemUsage(DiskMemUsageState state) override;
};

} // namespace proton
