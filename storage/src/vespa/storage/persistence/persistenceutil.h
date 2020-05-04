// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "types.h"
#include <vespa/persistence/spi/persistenceprovider.h>
#include <vespa/storage/common/servicelayercomponent.h>
#include <vespa/storage/persistence/filestorage/filestorhandler.h>
#include <vespa/storage/persistence/filestorage/filestormetrics.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/storage/storageutil/utils.h>
#include <vespa/config-stor-filestor.h>
#include <vespa/persistence/spi/persistenceprovider.h>

namespace storage {

struct PersistenceUtil;

class MessageTracker : protected Types {
public:
    typedef std::unique_ptr<MessageTracker> UP;

    MessageTracker(PersistenceUtil & env, MessageSender & replySender,
                   FileStorHandler::BucketLockInterface::SP bucketLock, api::StorageMessage::SP msg);

    ~MessageTracker();

    void setMetric(FileStorThreadMetrics::Op& metric);

    /**
     * Called by operation handlers to set reply if they need to send a
     * non-default reply. They should call this function as soon as they create
     * a reply, to ensure it is stored in case of failure after reply creation.
     */
    void setReply(api::StorageReply::SP reply) {
        assert( ! _reply );
        _reply = std::move(reply);
    }

    /** Utility function to be able to write a bit less in client. */
    void fail(uint32_t result, const String& message = "") {
        fail(ReturnCode((api::ReturnCode::Result)result, message));
    }
    /** Set the request to fail with the given failure. */
    void fail(const ReturnCode&);

    /** Don't send reply for the command being processed. Used by multi chain
     * commands like merge. */
    void dontReply() { _sendReply = false; }

    bool hasReply() const { return bool(_reply); }
    const api::StorageReply & getReply() const {
        return *_reply;
    }
    api::StorageReply & getReply() {
        return *_reply;
    }
    api::StorageReply::SP && stealReplySP() && {
        return std::move(_reply);
    }

    void generateReply(api::StorageCommand& cmd);

    api::ReturnCode getResult() const { return _result; }

    spi::Context & context() { return _context; }
    document::BucketId getBucketId() const {
        return _bucketLock->getBucket().getBucketId();
    }

    void sendReply();

    bool checkForError(const spi::Result& response);

    static MessageTracker::UP
    createForTesting(PersistenceUtil & env, MessageSender & replySender,
                     FileStorHandler::BucketLockInterface::SP bucketLock, api::StorageMessage::SP msg);

private:
    MessageTracker(PersistenceUtil & env, MessageSender & replySender, bool updateBucketInfo,
                   FileStorHandler::BucketLockInterface::SP bucketLock, api::StorageMessage::SP msg);
    bool                                     _sendReply;
    bool                                     _updateBucketInfo;
    FileStorHandler::BucketLockInterface::SP _bucketLock;
    api::StorageMessage::SP                  _msg;
    spi::Context                             _context;
    PersistenceUtil                         &_env;
    MessageSender                           &_replySender;
    FileStorThreadMetrics::Op               *_metric;
    api::StorageReply::SP                    _reply;
    api::ReturnCode                          _result;
    framework::MilliSecTimer                 _timer;
};

struct PersistenceUtil {
    vespa::config::content::StorFilestorConfig _config;
    ServiceLayerComponentRegister             &_compReg;
    ServiceLayerComponent                      _component;
    FileStorHandler                           &_fileStorHandler;
    uint16_t                                   _partition;
    uint16_t                                   _nodeIndex;
    FileStorThreadMetrics                     &_metrics;
    const document::BucketIdFactory           &_bucketFactory;
    const std::shared_ptr<const document::DocumentTypeRepo> _repo;
    spi::PersistenceProvider& _spi;

    PersistenceUtil(
            const config::ConfigUri&,
            ServiceLayerComponentRegister&,
            FileStorHandler& fileStorHandler,
            FileStorThreadMetrics& metrics,
            uint16_t partition,
            spi::PersistenceProvider& provider);

    ~PersistenceUtil();

    StorBucketDatabase& getBucketDatabase(document::BucketSpace bucketSpace)
        { return _component.getBucketDatabase(bucketSpace); }

    void updateBucketDatabase(const document::Bucket &bucket,
                              const api::BucketInfo& info);

    uint16_t getPreferredAvailableDisk(const document::Bucket &bucket) const;

    /** Lock the given bucket in the file stor handler. */
    struct LockResult {
        std::shared_ptr<FileStorHandler::BucketLockInterface> lock;
        uint16_t disk;

        LockResult() : lock(), disk(0) {}

        bool bucketExisted() const { return (lock.get() != 0); }
    };

    LockResult lockAndGetDisk(
            const document::Bucket &bucket,
            StorBucketDatabase::Flag flags = StorBucketDatabase::NONE);

    api::BucketInfo getBucketInfo(const document::Bucket &bucket, int disk = -1) const;

    api::BucketInfo convertBucketInfo(const spi::BucketInfo&) const;

    void setBucketInfo(MessageTracker& tracker, const document::Bucket &bucket);

    static uint32_t convertErrorCode(const spi::Result& response);

    void shutdown(const std::string& reason);
};

} // storage

