// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "matchers.h"
#include <vespa/searchcore/proton/attribute/attributemanager.h>
#include <vespa/searchcore/proton/common/docid_limit.h>
#include <vespa/searchcore/proton/documentmetastore/documentmetastorecontext.h>
#include <vespa/searchcore/proton/matching/match_context.h>
#include <vespa/searchcore/proton/matching/matcher.h>
#include <vespa/searchcore/proton/matching/sessionmanager.h>
#include <vespa/searchcorespi/index/indexsearchable.h>
#include <vespa/searchlib/attribute/attributevector.h>

namespace proton {

class MatchView {
    Matchers::SP                         _matchers;
    searchcorespi::IndexSearchable::SP   _indexSearchable;
    IAttributeManager::SP                _attrMgr;
    matching::SessionManager::SP         _sessionMgr;
    IDocumentMetaStoreContext::SP        _metaStore;
    DocIdLimit                          &_docIdLimit;

    size_t getNumDocs() const {
        return _metaStore->get().getNumActiveLids();
    }

public:
    typedef std::shared_ptr<MatchView> SP;
    MatchView(const MatchView &) = delete;
    MatchView & operator = (const MatchView &) = delete;

    MatchView(const Matchers::SP &matchers,
              const searchcorespi::IndexSearchable::SP &indexSearchable,
              const IAttributeManager::SP &attrMgr,
              const matching::SessionManager::SP &sessionMgr,
              const IDocumentMetaStoreContext::SP &metaStore,
              DocIdLimit &docIdLimit);

    const Matchers::SP & getMatchers() const { return _matchers; }
    const searchcorespi::IndexSearchable::SP & getIndexSearchable() const { return _indexSearchable; }
    const IAttributeManager::SP & getAttributeManager() const { return _attrMgr; }
    const matching::SessionManager::SP & getSessionManager() const { return _sessionMgr; }
    const IDocumentMetaStoreContext::SP & getDocumentMetaStore() const { return _metaStore; }
    DocIdLimit & getDocIdLimit(void) const { return _docIdLimit; }

    // Throws on error.
    matching::Matcher::SP getMatcher(const vespalib::string & rankProfile) const;

    matching::MatchingStats
    getMatcherStats(const vespalib::string &rankProfile) const {
        return _matchers->getStats(rankProfile);
    }

    matching::MatchContext::UP createContext() const;

    search::engine::SearchReply::UP
    match(const ISearchHandler::SP &searchHandler,
          const search::engine::SearchRequest &req,
          vespalib::ThreadBundle &threadBundle) const;
};

} // namespace proton

