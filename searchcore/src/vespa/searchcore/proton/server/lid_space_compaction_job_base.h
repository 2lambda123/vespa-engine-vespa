// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "blockable_maintenance_job.h"
#include "document_db_maintenance_config.h"
#include "i_disk_mem_usage_listener.h"
#include "iclusterstatechangedhandler.h"
#include <vespa/searchlib/common/idocumentmetastore.h>

namespace proton {
    class IDiskMemUsageNotifier;
    class IClusterStateChangedNotifier;
    class IOperationStorer;
    class ILidSpaceCompactionHandler;
    class IDocumentScanIterator;
    class RemoveOperationsRateTracker;
}

namespace proton {

/**
 * Job that regularly checks whether lid space compaction should be performed
 * for the given handler.
 *
 * Compaction is handled by moving documents from high lids to low free lids.
 * A handler is typically working over a single document sub db.
 */
class LidSpaceCompactionJobBase : public BlockableMaintenanceJob,
                                  public IDiskMemUsageListener,
                                  public IClusterStateChangedHandler
{
private:
    const DocumentDBLidSpaceCompactionConfig _cfg;
protected:
    ILidSpaceCompactionHandler   &_handler;
    IOperationStorer             &_opStorer;
    std::unique_ptr<IDocumentScanIterator>     _scanItr;
private:
    IDiskMemUsageNotifier        &_diskMemUsageNotifier;
    IClusterStateChangedNotifier &_clusterStateChangedNotifier;
    std::shared_ptr<RemoveOperationsRateTracker> _ops_rate_tracker;
    bool                          _is_disabled;
    bool                          _shouldCompactLidSpace;


    bool hasTooMuchLidBloat(const search::LidUsageStats &stats) const;
    bool shouldRestartScanDocuments(const search::LidUsageStats &stats) const;
    virtual bool scanDocuments(const search::LidUsageStats &stats) = 0;
    void compactLidSpace(const search::LidUsageStats &stats);
    bool remove_batch_is_ongoing() const;
    bool remove_is_ongoing() const;
protected:
    search::DocumentMetaData getNextDocument(const search::LidUsageStats &stats, bool retryLastDocument);
    bool scanDocumentsPost();
public:
    LidSpaceCompactionJobBase(const DocumentDBLidSpaceCompactionConfig &config,
                              ILidSpaceCompactionHandler &handler,
                              IOperationStorer &opStorer,
                              IDiskMemUsageNotifier &diskMemUsageNotifier,
                              const BlockableMaintenanceJobConfig &blockableConfig,
                              IClusterStateChangedNotifier &clusterStateChangedNotifier,
                              bool nodeRetired);
    ~LidSpaceCompactionJobBase() override;

    void notifyDiskMemUsage(DiskMemUsageState state) override;
    void notifyClusterStateChanged(const IBucketStateCalculator::SP &newCalc) override;
    bool run() override;
};

} // namespace proton

