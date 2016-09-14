// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "feedstate.h"
#include "i_operation_storer.h"
#include "idocumentmovehandler.h"
#include "ifeedview.h"
#include "igetserialnum.h"
#include "iheartbeathandler.h"
#include "ipruneremoveddocumentshandler.h"
#include "ireplayconfig.h"
#include "tlswriter.h"
#include "transactionlogmanager.h"
#include <vespa/searchcore/proton/common/doctypename.h>
#include <vespa/searchcore/proton/persistenceengine/i_resource_write_filter.h>
#include <vespa/searchcorespi/index/ithreadingservice.h>
#include <vespa/searchlib/transactionlog/translogclient.h>
#include <vespa/vespalib/util/blockingthreadstackexecutor.h>
#include <vespa/vespalib/util/executor.h>

namespace proton {
class ConfigStore;
class FeedConfigStore;
class IDocumentDBOwner;
class CreateBucketOperation;
class DDBState;

namespace bucketdb
{

class IBucketDBHandler;

}

/**
 * Class handling all aspects of feeding for a document database.
 * In addition to regular feeding this also includes handling the transaction log.
 */
class FeedHandler: private search::transactionlog::TransLogClient::Session::Callback,
                   public IDocumentMoveHandler,
                   public IPruneRemovedDocumentsHandler,
                   public IHeartBeatHandler,
                   public IOperationStorer,
                   public IGetSerialNum
{
private:
    typedef search::transactionlog::Packet  Packet;
    typedef search::transactionlog::RPC     RPC;
    typedef search::SerialNum               SerialNum;
    typedef storage::spi::Timestamp         Timestamp;
    typedef document::BucketId              BucketId;

public:
    /**
     * Interface defining the communication needed with the owner of the feed handler.
     */
    struct IOwner {
        virtual ~IOwner() {}
        virtual void performWipeHistory() = 0;
        virtual void onTransactionLogReplayDone() = 0;
        virtual void enterRedoReprocessState() = 0;
        virtual void onPerformPrune(SerialNum flushedSerial) = 0;
        virtual bool isFeedBlockedByRejectedConfig() = 0;
        virtual bool getAllowPrune() const = 0;
    };

private:
    class TlsMgrWriter : public TlsWriter {
        TransactionLogManager &_tls_mgr;
        search::transactionlog::Writer *_tlsDirectWriter;
    public:
        TlsMgrWriter(TransactionLogManager &tls_mgr,
                     search::transactionlog::Writer * tlsDirectWriter) :
            _tls_mgr(tls_mgr),
            _tlsDirectWriter(tlsDirectWriter)
        { }
        virtual void storeOperation(const FeedOperation &op);
        virtual bool erase(SerialNum oldest_to_keep);

        virtual SerialNum
        sync(SerialNum syncTo);
    };
    typedef searchcorespi::index::IThreadingService IThreadingService;

    IThreadingService                     &_writeService;
    DocTypeName                            _docTypeName;
    DDBState                              &_state;
    IOwner                                &_owner;
    const IResourceWriteFilter            &_writeFilter;
    IReplayConfig                         &_replayConfig;
    TransactionLogManager                  _tlsMgr;
    TlsMgrWriter                           _tlsMgrWriter;
    TlsWriter                             &_tlsWriter;
    TlsReplayProgress::UP                  _tlsReplayProgress;
    // the serial num of the last message in the transaction log
    SerialNum                              _serialNum;
    SerialNum                              _prunedSerialNum;
    bool                                   _delayedPrune;
    vespalib::Lock                         _feedLock;
    FeedState::SP                          _feedState;
    // used by master write thread tasks
    IFeedView                             *_activeFeedView;
    bucketdb::IBucketDBHandler            *_bucketDBHandler;
    PerDocTypeFeedMetrics                 &_metrics;

    vespalib::Lock                         _syncLock;
    SerialNum                              _syncedSerialNum; 
    bool                                   _allowSync; // Sanity check

    /**
     * Delayed handling of feed operations, in master write thread.
     * The current feed state is sampled here.
     */
    void doHandleOperation(FeedToken token, FeedOperation::UP op);

    bool considerWriteOperationForRejection(FeedToken *token, const FeedOperation &op);

    /**
     * Delayed execution of feed operations against feed view, in
     * master write thread.
     */
    void performPut(FeedToken::UP token, PutOperation &op);

    void performUpdate(FeedToken::UP token, UpdateOperation &op);
    void performInternalUpdate(FeedToken::UP token, UpdateOperation &op);
    void createNonExistingDocument(FeedToken::UP, const UpdateOperation &op);

    void performRemove(FeedToken::UP token, RemoveOperation &op);
private:
    void performGarbageCollect(FeedToken::UP token);

    void
    performCreateBucket(FeedToken::UP token, CreateBucketOperation &op);

    void performDeleteBucket(FeedToken::UP token, DeleteBucketOperation &op);
    void performSplit(FeedToken::UP token, SplitBucketOperation &op);
    void performJoin(FeedToken::UP token, JoinBucketsOperation &op);
    void performSync();

    /**
     * Used during callback from transaction log.
     */
    void handleTransactionLogEntry(const Packet::Entry &entry);
    void performEof();

    /**
     * Used when flushing is done
     */
    void performFlushDone(SerialNum flushedSerial);
    void performPrune(SerialNum flushedSerial);

public:
    void considerDelayedPrune();

private:
    /**
     * Returns the current feed state of this feed handler.
     */
    FeedState::SP getFeedState() const;

    /**
     * Used to handle feed state transitions.
     */
    void changeFeedState(FeedState::SP newState);

    void changeFeedState(FeedState::SP newState, const vespalib::LockGuard &feedGuard);

public:
    FeedHandler(const FeedHandler &) = delete;
    FeedHandler & operator = (const FeedHandler &) = delete;
    /**
     * Create a new feed handler.
     *
     * @param writeService  The thread service used for all write tasks.
     * @param tlsSpec       The spec to connect to the transaction log server.
     * @param docTypeName   The name and version of the document type we are feed handler for.
     * @param metrics       Feeding metrics.
     * @param state         Document db state
     * @param owner         Reference to the owner of this feed handler.
     * @param replayConfig  Reference to interface used for replaying config changes.
     * @param writer        Inject writer for tls, or NULL to use internal.
     */
    FeedHandler(IThreadingService &writeService,
                const vespalib::string &tlsSpec,
                const DocTypeName &docTypeName,
                PerDocTypeFeedMetrics &metrics,
                DDBState &state,
                IOwner &owner,
                const IResourceWriteFilter &writerFilter,
                IReplayConfig &replayConfig,
                search::transactionlog::Writer *writer,
                TlsWriter *tlsWriter = NULL);

    virtual
    ~FeedHandler();

    /**
     * Init this feed handler.
     *
     * @param oldestConfigSerial The serial number of the oldest config snapshot.
     */
    void
    init(SerialNum oldestConfigSerial);

    /**
     * Close this feed handler and its components.
     */
    void
    close();

    /**
     * Start replay of the transaction log.
     *
     * @param flushedIndexMgrSerial   The flushed serial number of the
     *                                index manager.
     * @param flushedSummaryMgrSerial The flushed serial number of the
     *                                document store.
     * @param config_store            Reference to the config store.
     */

    void
    replayTransactionLog(SerialNum flushedIndexMgrSerial,
                         SerialNum flushedSummaryMgrSerial,
                         SerialNum oldestFlushedSerial,
                         SerialNum newestFlushedSerial,
                         ConfigStore &config_store);

    /**
     * Called when a flush is done and allows pruning of the transaction log.
     *
     * @param flushedSerial serial number flushed for all relevant flush targets.
     */
    void
    flushDone(SerialNum flushedSerial);

    /**
     * Used to flip between normal and recovery feed states.
     */
    void changeToNormalFeedState();

    /**
     * Update the active feed view.
     * Always called by the master write thread so locking is not needed.
     */
    void
    setActiveFeedView(IFeedView *feedView)
    {
        _activeFeedView = feedView;
    }

    void
    setBucketDBHandler(bucketdb::IBucketDBHandler *bucketDBHandler)
    {
        _bucketDBHandler = bucketDBHandler;
    }

    /**
     * Wait until transaction log is replayed.
     */
    void waitForReplayDone();

    void setSerialNum(SerialNum serialNum) { _serialNum = serialNum; }
    SerialNum incSerialNum() { return ++_serialNum; }
    SerialNum getSerialNum() const override { return _serialNum; }
    SerialNum getPrunedSerialNum() const { return _prunedSerialNum; }

    void setReplayDone();
    bool getReplayDone() const;
    bool isDoingReplay() const;
    float getReplayProgress() const {
        return _tlsReplayProgress.get() != nullptr ? _tlsReplayProgress->getProgress() : 0;
    }
    bool getTransactionLogReplayDone() const;
    vespalib::string getDocTypeName() const { return _docTypeName.getName(); }
    void tlsPrune(SerialNum oldest_to_keep);

    void performOperation(FeedToken::UP token, FeedOperation::UP op);
    void handleOperation(FeedToken token, FeedOperation::UP op);

    /**
     * Implements IDocumentMoveHandler
     */
    virtual void
    handleMove(MoveOperation &op);

    /**
     * Implements IHeartBeatHandler
     */
    virtual void
    heartBeat(void);

    virtual void
    sync(void);

    /**
     * Implements TransLogClient::Session::Callback.
     */
    virtual RPC::Result
    receive(const Packet &packet);

    virtual void
    eof(void);

    virtual void
    inSync(void);

    /**
     * Implements IPruneRemovedDocumentsHandler
     */
    void
    performPruneRemovedDocuments(PruneRemovedDocumentsOperation &pruneOp);

    void
    syncTls(SerialNum syncTo);

    void
    storeRemoteOperation(const FeedOperation &op);

    // Implements IOperationStorer
    virtual void storeOperation(FeedOperation &op);
};

} // namespace proton

