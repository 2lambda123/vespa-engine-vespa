// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "partitionstate.h"
#include "bucketinfo.h"
#include "bucket.h"
#include "docentry.h"

namespace storage {

namespace spi {

class Result {
public:
    typedef std::unique_ptr<Result> UP;

    enum ErrorType {
        NONE,
        TRANSIENT_ERROR,
        PERMANENT_ERROR,
        TIMESTAMP_EXISTS,
        FATAL_ERROR,
        RESOURCE_EXHAUSTED,
        ERROR_COUNT
    };

    /**
     * Constructor to use for a result where there is no error.
     */
    Result() : _errorCode(NONE), _errorMessage() {}

    /**
     * Constructor to use when an error has been detected.
     */
    Result(ErrorType error, const vespalib::string& errorMessage)
        : _errorCode(error),
          _errorMessage(errorMessage) {}

    virtual ~Result();

    bool operator==(const Result& o) const {
        return _errorCode == o._errorCode
               && _errorMessage == o._errorMessage;
    }

    bool hasError() const {
        return _errorCode != NONE;
    }

    ErrorType getErrorCode() const {
        return _errorCode;
    }

    const vespalib::string& getErrorMessage() const {
        return _errorMessage;
    }

    vespalib::string toString() const;

private:
    ErrorType _errorCode;
    vespalib::string _errorMessage;
};

std::ostream & operator << (std::ostream & os, const Result & r);

class BucketInfoResult : public Result {
public:
    /**
     * Constructor to use for a result where an error has been detected.
     * The service layer will not update the bucket information in this case,
     * so it should not be returned either.
     */
    BucketInfoResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage) {};

    /**
     * Constructor to use when the write operation was successful,
     * and the bucket info was modified.
     */
    BucketInfoResult(const BucketInfo& info) : _info(info) {}

    const BucketInfo& getBucketInfo() const {
        return _info;
    }

private:
    BucketInfo _info;
};

class UpdateResult : public Result
{
public:
    /**
     * Constructor to use for a result where an error has been detected.
     * The service layer will not update the bucket information in this case,
     * so it should not be returned either.
     */
    UpdateResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage),
          _existingTimestamp(0) { }

    /**
     * Constructor to use when no document to update was found.
     */
    UpdateResult()
        : _existingTimestamp(0) { }

    /**
     * Constructor to use when the update was successful.
     */
    UpdateResult(Timestamp existingTimestamp)
        : _existingTimestamp(existingTimestamp) {}

    Timestamp getExistingTimestamp() const { return _existingTimestamp; }

private:
    // Set to 0 if non-existing.
    Timestamp _existingTimestamp;
};

class RemoveResult : public Result
{
public:
    /**
     * Constructor to use for a result where an error has been detected.
     * The service layer will not update the bucket information in this case,
     * so it should not be returned either.
     */
    RemoveResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage),
          _wasFound(false)
    { }

    /**
     * Constructor to use when the remove was successful.
     */
    RemoveResult(bool foundDocument)
        : _wasFound(foundDocument) { }

    bool wasFound() const { return _wasFound; }

private:
    bool _wasFound;
};

class GetResult : public Result {
public:
    /**
     * Constructor to use when there was an error retrieving the document.
     * Not finding the document is not an error in this context.
     */
    GetResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage),
          _timestamp(0) { }

    /**
     * Constructor to use when we didn't find the document in question.
     */
    GetResult()
        : _timestamp(0) { }

    /**
     * Constructor to use when we found the document asked for.
     *
     * @param doc The document we found
     * @param timestamp The timestamp with which the document was stored.
     */
    GetResult(DocumentUP doc, Timestamp timestamp);

    ~GetResult();

    Timestamp getTimestamp() const { return _timestamp; }

    bool hasDocument() const {
        return _doc.get() != NULL;
    }

    const Document& getDocument() const {
        return *_doc;
    }

    Document& getDocument() {
        return *_doc;
    }

    const DocumentSP & getDocumentPtr() {
        return _doc;
    }

private:
    Timestamp  _timestamp;
    DocumentSP _doc;
};

class BucketIdListResult : public Result {
public:
    typedef document::BucketId::List List;

    /**
     * Constructor used when there was an error listing the buckets.
     */
    BucketIdListResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage) {}

    /**
     * Constructor used when the bucket listing was successful.
     *
     * @param list The list of bucket ids this partition has. Is swapped with
     * the list internal to this object.
     */
    BucketIdListResult(List& list)
        : Result()
    {
        _info.swap(list);
    }

    ~BucketIdListResult();

    const List& getList() const { return _info; }
    List& getList() { return _info; }

private:
    List _info;
};

class CreateIteratorResult : public Result {
public:
    /**
     * Constructor used when there was an error creating the iterator.
     */
    CreateIteratorResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage),
          _iterator(0) { }

    /**
     * Constructor used when the iterator state was successfully created.
     */
    CreateIteratorResult(const IteratorId& id)
        : _iterator(id)
    { }

    const IteratorId& getIteratorId() const { return _iterator; }

private:
    IteratorId _iterator;
};

class IterateResult : public Result {
public:
    typedef std::vector<DocEntry::LP> List;

    /**
     * Constructor used when there was an error creating the iterator.
     */
    IterateResult(ErrorType error, const vespalib::string& errorMessage)
        : Result(error, errorMessage),
          _completed(false)
    { }

    /**
     * Constructor used when the iteration was successful.
     * For performance concerns, the entries in the input vector
     * are swapped with the internal vector.
     *
     * @param completed Set to true if iteration has been completed.
     */
    IterateResult(List entries, bool completed)
        : _completed(completed),
          _entries(std::move(entries))
    { }

    ~IterateResult();

    const List& getEntries() const { return _entries; }

    bool isCompleted() const { return _completed; }

private:
    bool _completed;
    std::vector<DocEntry::LP> _entries;
};

class PartitionStateListResult : public Result
{
public:
    /**
     * Constructor to use for a result where an error has been detected.
     */
    PartitionStateListResult(ErrorType error, const vespalib::string& msg)
        : Result(error, msg),
          _list(0)
    { }

    /**
     * Constructor to use when the operation was successful.
     */
    PartitionStateListResult(PartitionStateList list) : _list(list) { }

    const PartitionStateList & getList() const { return _list; }

private:
    PartitionStateList _list;
};

}  // namespace spi
}  // namespace storage

