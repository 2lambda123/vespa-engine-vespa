// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/document/bucket/bucketid.h>
#include <vespa/document/bucket/bucketidfactory.h>
#include <vespa/vdslib/state/clusterstate.h>
#include <vespa/storage/distributor/distributorcomponent.h>
#include <vespa/storage/distributor/visitormetricsset.h>
#include <vespa/storageapi/messageapi/messagehandler.h>
#include <vespa/storageframework/storageframework.h>
#include <chrono>

namespace storage {

class DistributorMetricSet;
class PersistenceOperationMetricSet;

namespace distributor {

class Distributor;
class MaintenanceOperationGenerator;

class ExternalOperationHandler : public DistributorComponent,
                                 public api::MessageHandler
{
public:
    using Clock = std::chrono::system_clock;
    using TimePoint = std::chrono::time_point<Clock>;

    DEF_MSG_COMMAND_H(Get);
    DEF_MSG_COMMAND_H(Put);
    DEF_MSG_COMMAND_H(Update);
    DEF_MSG_COMMAND_H(Remove);
    DEF_MSG_COMMAND_H(RemoveLocation);
    DEF_MSG_COMMAND_H(MultiOperation);
    DEF_MSG_COMMAND_H(StatBucket);
    DEF_MSG_COMMAND_H(CreateVisitor);
    DEF_MSG_COMMAND_H(GetBucketList);

    ExternalOperationHandler(Distributor& owner,
                             const MaintenanceOperationGenerator&,
                             DistributorComponentRegister& compReg);

    ~ExternalOperationHandler();

    bool handleMessage(const std::shared_ptr<api::StorageMessage>& msg,
                       Operation::SP& operation);

    void rejectFeedBeforeTimeReached(TimePoint timePoint) noexcept {
        _rejectFeedBeforeTimeReached = timePoint;
    }

private:
    metrics::LoadMetric<VisitorMetricSet> _visitorMetrics;
    const MaintenanceOperationGenerator& _operationGenerator;
    Operation::SP _op;
    TimePoint _rejectFeedBeforeTimeReached;

    bool checkSafeTimeReached(api::StorageCommand& cmd);
    api::ReturnCode makeSafeTimeRejectionResult(TimePoint unsafeTime);
    bool checkTimestampMutationPreconditions(
            api::StorageCommand& cmd,
            const document::BucketId& bucket,
            PersistenceOperationMetricSet& persistenceMetrics);

    DistributorMetricSet& getMetrics() { return getDistributor().getMetrics(); }
};

}

}

