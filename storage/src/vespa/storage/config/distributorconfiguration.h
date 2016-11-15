// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/storage/config/config-stor-distributormanager.h>
#include <vespa/storage/config/config-stor-visitordispatcher.h>
#include <vespa/vespalib/stllike/hash_set.h>
#include <vespa/storage/common/storagecomponent.h>
#include <chrono>

namespace storage {

namespace distributor {
    class Distributor_Test;
}

class DistributorConfiguration {
public: 
    DistributorConfiguration(StorageComponent& component);

    struct MaintenancePriorities
    {
        // Defaults for these are chosen as those used as the current (non-
        // configurable) values at the time of implementation.
        uint8_t mergeMoveToIdealNode {120};
        uint8_t mergeOutOfSyncCopies {120};
        uint8_t mergeTooFewCopies {120};
        uint8_t activateNoExistingActive {100};
        uint8_t activateWithExistingActive {100};
        uint8_t deleteBucketCopy {100};
        uint8_t joinBuckets {155};
        uint8_t splitDistributionBits {200};
        uint8_t splitLargeBucket {175};
        uint8_t splitInconsistentBucket {110};
        uint8_t garbageCollection {200};
    };

    using DistrConfig = vespa::config::content::core::StorDistributormanagerConfig;
    
    void configure(const DistrConfig& config);

    void configure(const vespa::config::content::core::StorVisitordispatcherConfig& config);
        
    void setIdealStateChunkSize(uint32_t chunkSize) {
        _idealStateChunkSize = chunkSize;
    }
    
    uint32_t getIdealStateChunkSize() { 
        return _idealStateChunkSize;
    }

    uint32_t lastGarbageCollectionChangeTime() const {
        return _lastGarbageCollectionChange;
    }

    const std::string& getGarbageCollectionSelection() const {
        return _garbageCollectionSelection;
    }

    uint32_t getGarbageCollectionInterval() const {
        return _garbageCollectionInterval;
    }

    void setGarbageCollection(const std::string& selection, uint32_t interval) {
        _garbageCollectionSelection = selection;
        _garbageCollectionInterval = interval;
    }

    void setLastGarbageCollectionChangeTime(uint32_t lastChangeTime) {
        _lastGarbageCollectionChange = lastChangeTime;
    }

    bool stateCheckerIsActive(const vespalib::stringref & stateCheckerName) const {
        return _blockedStateCheckers.find(stateCheckerName) == _blockedStateCheckers.end();
    }

    void disableStateChecker(const vespalib::stringref & stateCheckerName) {
        _blockedStateCheckers.insert(stateCheckerName);
    }

    void setDoInlineSplit(bool value) {
        _doInlineSplit = value;
    }
    
    bool doInlineSplit() const {
        return _doInlineSplit;
    }

    /**
       Sets the number of documents needed for a bucket to be split.

       @param count The minimum number of documents a bucket needs to have to be split.
    */
    void setSplitCount(uint32_t count) { _docCountSplitLimit = count; }

    /**
       Sets the number of bytes needed for a bucket to be split.

       @param sz The minimum size (in bytes) a bucket needs to have in order to be split.
    */
    void setSplitSize(uint32_t sz) { _byteCountSplitLimit = sz; }

    /**
       Sets the maximum number of documents two buckets can have in order to be joined. The sum
       of the documents in the two buckets need to be below this limit for join to occur.

       @param count The maximum number of documents two buckets need to have in order to be joined.
    */
    void setJoinCount(uint32_t count) { _docCountJoinLimit = count; }

    /**
       Sets the maximum number of stored bytes two buckets can have in order to be joined. The sum
       of the sizes of the two buckets need to be below this limit for join to occur.

       @param count The maximum size the two buckets need to have in order to be joined.
    */
    void setJoinSize(uint32_t sz) { _byteCountJoinLimit = sz; }

    /**
       Sets the minimal bucket split level we want buckets to have. Buckets that have fewer used bits
       than this are automatically split.

       @param splitBits The minimal bucket split level.
    */
    void setMinimalBucketSplit(int splitBits) { _minimalBucketSplit = splitBits; };

    /**
       Sets the maximum number of ideal state operations a distributor should
       schedule to each storage node.

       @param numOps The number of operations to schedule.
    */
    void setMaxIdealStateOperations(uint32_t numOps) { 
        _maxIdealStateOperations = numOps; 
    };

    uint32_t getMaxIdealStateOperations() {
        return _maxIdealStateOperations;
    }

    void setMaintenancePriorities(const MaintenancePriorities& mp) {
        _maintenancePriorities = mp;
    }

    const MaintenancePriorities& getMaintenancePriorities() const {
        return _maintenancePriorities;
    }
    
    /**
       @see setSplitCount
    */
    uint32_t getSplitCount() const { return _docCountSplitLimit; }

    /**
       @see setSplitSize
    */
    uint32_t getSplitSize() const { return _byteCountSplitLimit; }

    /**
       @see setJoinCount
    */
    uint32_t getJoinCount() const { return _docCountJoinLimit; }

    /**
       @see setJoinSize
    */
    uint32_t getJoinSize() const { return _byteCountJoinLimit; }

    /**
       @see setMinimalBucketSplit
    */
    uint32_t getMinimalBucketSplit() const { return _minimalBucketSplit; };

    uint32_t getMinPendingMaintenanceOps() const {
        return _minPendingMaintenanceOps;
    }
    void setMinPendingMaintenanceOps(uint32_t minPendingMaintenanceOps) {
        _minPendingMaintenanceOps = minPendingMaintenanceOps;
    }
    uint32_t getMaxPendingMaintenanceOps() const {
        return _maxPendingMaintenanceOps;
    }
    void setMaxPendingMaintenanceOps(uint32_t maxPendingMaintenanceOps) {
        _maxPendingMaintenanceOps = maxPendingMaintenanceOps;
    }

    uint32_t getMaxVisitorsPerNodePerClientVisitor() const {
        return _maxVisitorsPerNodePerClientVisitor;
    }
    uint32_t getMinBucketsPerVisitor() const {
        return _minBucketsPerVisitor;
    }
    int64_t  getMinTimeLeftToResend() const {
        return _minTimeLeftToResend;
    }

    void setMaxVisitorsPerNodePerClientVisitor(uint32_t n) {
        _maxVisitorsPerNodePerClientVisitor = n;
    }
    void setMinBucketsPerVisitor(uint32_t n) {
        _minBucketsPerVisitor = n;
    }
    void setMinTimeLeftToResend(int64_t minTime) {
        _minTimeLeftToResend = minTime;
    }
    uint32_t getMaxNodesPerMerge() const {
        return _maxNodesPerMerge;
    }
    bool getEnableJoinForSiblingLessBuckets() const {
        return _enableJoinForSiblingLessBuckets;
    }
    bool getEnableInconsistentJoin() const noexcept {
        return _enableInconsistentJoin;
    }

    bool getEnableHostInfoReporting() const noexcept {
        return _enableHostInfoReporting;
    }

    using ReplicaCountingMode = DistrConfig::MinimumReplicaCountingMode;
    void setMinimumReplicaCountingMode(ReplicaCountingMode mode) noexcept {
        _minimumReplicaCountingMode = mode;
    }
    ReplicaCountingMode getMinimumReplicaCountingMode() const noexcept {
        return _minimumReplicaCountingMode;
    }
    bool isBucketActivationDisabled() const noexcept {
        return _disableBucketActivation;
    }
    std::chrono::seconds getMaxClusterClockSkew() const noexcept {
        return _maxClusterClockSkew;
    }
    
private:
    DistributorConfiguration(const DistributorConfiguration& other);
    DistributorConfiguration& operator=(const DistributorConfiguration& other);
    
    StorageComponent& _component;
    
    uint32_t _byteCountSplitLimit;
    uint32_t _docCountSplitLimit;
    uint32_t _byteCountJoinLimit;
    uint32_t _docCountJoinLimit;
    uint32_t _minimalBucketSplit;
    uint32_t _maxIdealStateOperations;
    uint32_t _idealStateChunkSize;
    uint32_t _maxNodesPerMerge;

    std::string _garbageCollectionSelection;

    uint32_t _lastGarbageCollectionChange;
    uint32_t _garbageCollectionInterval;

    uint32_t _minPendingMaintenanceOps;
    uint32_t _maxPendingMaintenanceOps;

    vespalib::hash_set<vespalib::string> _blockedStateCheckers;

    uint32_t _maxVisitorsPerNodePerClientVisitor;
    uint32_t _minBucketsPerVisitor;
    int64_t  _minTimeLeftToResend;

    MaintenancePriorities _maintenancePriorities;
    std::chrono::seconds _maxClusterClockSkew;

    bool _doInlineSplit;
    bool _enableJoinForSiblingLessBuckets;
    bool _enableInconsistentJoin;
    bool _enableHostInfoReporting;
    bool _disableBucketActivation;

    DistrConfig::MinimumReplicaCountingMode _minimumReplicaCountingMode;
    
    friend class distributor::Distributor_Test;
    
    bool containsTimeStatement(const std::string& documentSelection) const; 
    void configureMaintenancePriorities(
            const vespa::config::content::core::StorDistributormanagerConfig&);
};

}


