// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchcore/proton/documentmetastore/i_document_meta_store.h>
#include <vespa/searchcore/proton/persistenceengine/i_document_retriever.h>

namespace proton
{

/**
 * The view of a document sub db as seen from the maintenance controller
 * and various maintenance jobs.
 */
class MaintenanceDocumentSubDB
{
public:
    IDocumentMetaStore::SP   _metaStore;
    IDocumentRetriever::SP   _retriever;
    uint32_t                 _subDbId;

    MaintenanceDocumentSubDB()
        : _metaStore(),
          _retriever(),
          _subDbId(0u)
    {
    }
    ~MaintenanceDocumentSubDB();

    MaintenanceDocumentSubDB(const IDocumentMetaStore::SP & metaStore,
                             const IDocumentRetriever::SP & retriever,
                             uint32_t subDbId)
        : _metaStore(metaStore),
          _retriever(retriever),
          _subDbId(subDbId)
    {
    }

    bool valid() const { return bool(_metaStore); }

    void clear() {
        _metaStore.reset();
        _retriever.reset();
        _subDbId = 0u;
    } 
};


} // namespace proton
