// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.server.bucketmovejob");
#include "bucketmovejob.h"
#include "imaintenancejobrunner.h"
#include "ibucketstatechangednotifier.h"
#include "iclusterstatechangednotifier.h"
#include "maintenancedocumentsubdb.h"
#include "i_disk_mem_usage_notifier.h"
#include <vespa/searchcore/proton/documentmetastore/i_document_meta_store.h>

using document::BucketId;
using storage::spi::BucketInfo;

namespace proton {

namespace {

const uint32_t FIRST_SCAN_PASS = 1;
const uint32_t SECOND_SCAN_PASS = 2;

const char * bool2str(bool v) { return (v ? "T" : "F"); }

}


BucketMoveJob::ScanIterator::
ScanIterator(BucketDBOwner::Guard db, uint32_t pass, BucketId lastBucket, BucketId endBucket)
    : _db(std::move(db)),
      _itr(lastBucket.isSet() ? _db->upperBound(lastBucket) : _db->begin()),
      _end(pass == SECOND_SCAN_PASS && endBucket.isSet() ?
           _db->upperBound(endBucket) : _db->end())
{
}


BucketMoveJob::ScanIterator::
ScanIterator(BucketDBOwner::Guard db, BucketId bucket)
    : _db(std::move(db)),
      _itr(_db->lowerBound(bucket)),
      _end(_db->end())
{
}


BucketMoveJob::ScanIterator::ScanIterator(ScanIterator &&rhs)
    : _db(std::move(rhs._db)),
      _itr(rhs._itr),
      _end(rhs._end)
{
}

void
BucketMoveJob::checkBucket(const BucketId &bucket,
                           ScanIterator &itr,
                           DocumentBucketMover &mover,
                           IFrozenBucketHandler::ExclusiveBucketGuard::UP & bucketGuard)
{
    bool hasReadyDocs = itr.hasReadyBucketDocs();
    bool hasNotReadyDocs = itr.hasNotReadyBucketDocs();
    if (!hasReadyDocs && !hasNotReadyDocs) {
        return; // No documents for bucket in ready or notready subdbs
    }
    bool shouldBeReady = _calc->shouldBeReady(bucket);
    bool isActive = itr.isActive();
    bool wantReady = shouldBeReady || isActive;
    LOG(spam, "checkBucket(): bucket(%s), shouldBeReady(%s), active(%s)",
              bucket.toString().c_str(), bool2str(shouldBeReady), bool2str(isActive));
    if (wantReady) {
        if (!hasNotReadyDocs)
            return; // No notready bucket to make ready
    } else {
        if (!hasReadyDocs)
            return; // No ready bucket to make notready
    }
    bucketGuard = _frozenBuckets.acquireExclusiveBucket(bucket);
    if ( ! bucketGuard ) {
        LOG(debug, "checkBucket(): delay frozen bucket: (%s)", bucket.toString().c_str());
        _delayedBucketsFrozen.insert(bucket);
        _delayedBuckets.erase(bucket);
        return;
    }
    const MaintenanceDocumentSubDB &source(wantReady ? _notReady : _ready);
    const MaintenanceDocumentSubDB &target(wantReady ? _ready : _notReady);
    LOG(debug, "checkBucket(): mover.setupForBucket(%s, source:%u, target:%u)",
        bucket.toString().c_str(), source._subDbId, target._subDbId);
    mover.setupForBucket(bucket, &source, target._subDbId,
                         _moveHandler, _ready._metaStore->getBucketDB());
}


BucketMoveJob::ScanResult
BucketMoveJob::scanBuckets(size_t maxBucketsToScan, IFrozenBucketHandler::ExclusiveBucketGuard::UP & bucketGuard)
{
    size_t bucketsScanned = 0;
    bool passDone = false;
    ScanIterator itr(_ready._metaStore->getBucketDB().takeGuard(),
            _scanPass, _scanPos._lastBucket, _endPos._lastBucket);
    BucketId bucket;
    for (; itr.valid() &&
             bucketsScanned < maxBucketsToScan && _mover.bucketDone();
         ++itr, ++bucketsScanned)
    {
        bucket = itr.getBucket();
        _scanPos._lastBucket = bucket;
        checkBucket(bucket, itr, _mover, bucketGuard);
    }
    if (!itr.valid()) {
        passDone = true;
        _scanPos._lastBucket = BucketId();
    }
    return ScanResult(bucketsScanned, passDone);
}


void
BucketMoveJob::moveDocuments(DocumentBucketMover &mover,
                             size_t maxDocsToMove,
                             IFrozenBucketHandler::ExclusiveBucketGuard::UP & bucketGuard)
{
    if ( ! bucketGuard ) {
        bucketGuard = _frozenBuckets.acquireExclusiveBucket(mover.getBucket());
        if (! bucketGuard) {
            maybeDelayMover(mover, mover.getBucket());
            return;
        }
    }
    assert(mover.getBucket() == bucketGuard->getBucket());
    mover.moveDocuments(maxDocsToMove);
    if (mover.bucketDone()) {
        _modifiedHandler.notifyBucketModified(mover.getBucket());
    }
}


BucketMoveJob::
BucketMoveJob(const IBucketStateCalculator::SP &calc,
              IDocumentMoveHandler &moveHandler,
              IBucketModifiedHandler &modifiedHandler,
              const MaintenanceDocumentSubDB &ready,
              const MaintenanceDocumentSubDB &notReady,
              IFrozenBucketHandler &frozenBuckets,
              IClusterStateChangedNotifier &clusterStateChangedNotifier,
              IBucketStateChangedNotifier &bucketStateChangedNotifier,
              IDiskMemUsageNotifier &diskMemUsageNotifier,
              const vespalib::string &docTypeName)
    : IMaintenanceJob("move_buckets." + docTypeName, 0.0, 0.0),
      IClusterStateChangedHandler(),
      IBucketFreezeListener(),
      _calc(calc),
      _moveHandler(moveHandler),
      _modifiedHandler(modifiedHandler),
      _ready(ready),
      _notReady(notReady),
      _mover(),
      _doneScan(false),
      _scanPos(),
      _scanPass(FIRST_SCAN_PASS),
      _endPos(),
      _delayedBuckets(),
      _delayedBucketsFrozen(),
      _frozenBuckets(frozenBuckets),
      _delayedMover(),
      _runner(nullptr),
      _clusterUp(false),
      _nodeUp(false),
      _nodeInitializing(false),
      _resourcesOK(false),
      _runnable(false),
      _clusterStateChangedNotifier(clusterStateChangedNotifier),
      _bucketStateChangedNotifier(bucketStateChangedNotifier),
      _diskMemUsageNotifier(diskMemUsageNotifier)
{
    refreshDerivedClusterState();
    
    _frozenBuckets.addListener(this);
    _clusterStateChangedNotifier.addClusterStateChangedHandler(this);
    _bucketStateChangedNotifier.addBucketStateChangedHandler(this);
    _diskMemUsageNotifier.addDiskMemUsageListener(this);
}


BucketMoveJob::~BucketMoveJob()
{
    _frozenBuckets.removeListener(this);
    _clusterStateChangedNotifier.removeClusterStateChangedHandler(this);
    _bucketStateChangedNotifier.removeBucketStateChangedHandler(this);
    _diskMemUsageNotifier.removeDiskMemUsageListener(this);
}


void
BucketMoveJob::maybeCancelMover(DocumentBucketMover &mover)
{
    // Cancel bucket if moving in wrong direction
    if (!mover.bucketDone()) {
        bool ready = mover.getSource() == &_ready;
        if (!_runnable ||
            _calc->shouldBeReady(mover.getBucket()) == ready) {
            mover.cancel();
        }
    }
}


void
BucketMoveJob::maybeDelayMover(DocumentBucketMover &mover, BucketId bucket)
{
    // Delay bucket if being frozen.
    if (!mover.bucketDone() && bucket == mover.getBucket()) {
        mover.cancel();
        _delayedBucketsFrozen.insert(bucket);
        _delayedBuckets.erase(bucket);
    }
}

void
BucketMoveJob::notifyThawedBucket(const BucketId &bucket)
{
    if (_delayedBucketsFrozen.erase(bucket) != 0u) {
        _delayedBuckets.insert(bucket);
        if (_runner && _runnable) {
            _runner->run();
        }
    }
}


void
BucketMoveJob::deactivateBucket(BucketId bucket)
{
    _delayedBuckets.insert(bucket);
}


void
BucketMoveJob::activateBucket(BucketId bucket)
{
    BucketDBOwner::Guard notReadyBdb(_notReady._metaStore->getBucketDB().takeGuard());
    if (notReadyBdb->get(bucket).getDocumentCount() == 0) {
        return; // notready bucket already empty. This is the normal case.
    }
    _delayedBuckets.insert(bucket);
}


void
BucketMoveJob::changedCalculator()
{
    if (done()) {
        _scanPos = ScanPosition();
        _endPos = ScanPosition();
    } else {
        _endPos = _scanPos;
    }
    _doneScan = false;
    _scanPass = FIRST_SCAN_PASS;
    maybeCancelMover(_mover);
    maybeCancelMover(_delayedMover);
}


void
BucketMoveJob::scanAndMove(size_t maxBucketsToScan,
                           size_t maxDocsToMove)
{
    if (done()) {
        return;
    }
    IFrozenBucketHandler::ExclusiveBucketGuard::UP bucketGuard;
    // Look for delayed bucket to be processed now
    while (!_delayedBuckets.empty() && _delayedMover.bucketDone()) {
        const BucketId bucket = *_delayedBuckets.begin();
        _delayedBuckets.erase(_delayedBuckets.begin());
        ScanIterator itr(_ready._metaStore->getBucketDB().takeGuard(), bucket);
        if (itr.getBucket() == bucket) {
            checkBucket(bucket, itr, _delayedMover, bucketGuard);
        }
    }
    if (!_delayedMover.bucketDone()) {
        moveDocuments(_delayedMover, maxDocsToMove, bucketGuard);
        return;
    }
    if (_mover.bucketDone()) {
        size_t bucketsScanned = 0;
        for (;;) {
            if (_mover.bucketDone()) {
                ScanResult res = scanBuckets(maxBucketsToScan -
                                             bucketsScanned, bucketGuard);
                bucketsScanned += res.first;
                if (res.second) {
                    if (_scanPass == FIRST_SCAN_PASS &&
                        _endPos.validBucket()) {
                        _scanPos = ScanPosition();
                        _scanPass = SECOND_SCAN_PASS;
                    } else {
                        _doneScan = true;
                        break;
                    }
                }
            }
            if (!_mover.bucketDone() || bucketsScanned >= maxBucketsToScan) {
                break;
            }
        }
    }
    if (!_mover.bucketDone()) {
        moveDocuments(_mover, maxDocsToMove, bucketGuard);
    }
}

void
BucketMoveJob::registerRunner(IMaintenanceJobRunner *runner)
{
    _runner = runner;
}


bool
BucketMoveJob::run()
{
    if (!_runnable)
        return true; // indicate work is done, since node state is bad
    scanAndMove(200, 1);
    return done();
}

void
BucketMoveJob::refreshRunnable()
{
    _runnable = _clusterUp && _nodeUp && !_nodeInitializing && _resourcesOK;
}

void
BucketMoveJob::refreshDerivedClusterState()
{
    _clusterUp = _calc.get() != NULL && _calc->clusterUp();
    _nodeUp = _calc.get() != NULL && _calc->nodeUp();
    _nodeInitializing = _calc.get() != NULL && _calc->nodeInitializing();
    refreshRunnable();
}

void
BucketMoveJob::notifyClusterStateChanged(const IBucketStateCalculator::SP &
                                         newCalc)
{
    // Called by master write thread
    _calc = newCalc;
    refreshDerivedClusterState();
    changedCalculator();
    if (_runner && _runnable) {
        _runner->run();
    }
}

void
BucketMoveJob::notifyBucketStateChanged(const BucketId &bucketId,
                                        BucketInfo::ActiveState newState)
{
    // Called by master write thread
    if (newState == BucketInfo::NOT_ACTIVE) {
        deactivateBucket(bucketId);
    } else {
        activateBucket(bucketId);
    }
    if (!done() && _runner && _runnable) {
        _runner->run();
    }
}

void BucketMoveJob::notifyDiskMemUsage(DiskMemUsageState state)
{
    // Called by master write thread
    bool resourcesOK = !state.aboveDiskLimit();
    _resourcesOK = resourcesOK;
    refreshRunnable();
    if (_runner && _runnable) {
        _runner->run();
    }
}

} // namespace proton
