// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/searchlib/engine/docsumreply.h>
#include <vespa/searchlib/engine/docsumrequest.h>
#include <vespa/searchlib/engine/searchreply.h>
#include <vespa/searchlib/engine/searchrequest.h>
#include <vespa/vespalib/util/thread_bundle.h>

namespace proton {

/**
 * This interface describes a sync summary operation handler. It is
 * implemented by the DocumentDB class, and used by the SummaryEngine
 * class to delegate operations to the appropriate db.
 */
class ISearchHandler {
public:
    /**
     * Convenience typedefs.
     */
    typedef std::unique_ptr<ISearchHandler> UP;
    typedef std::shared_ptr<ISearchHandler> SP;

    ISearchHandler(const ISearchHandler &) = delete;
    ISearchHandler & operator = (const ISearchHandler &) = delete;
    /**
     * Virtual destructor to allow inheritance.
     */
    virtual ~ISearchHandler() { }

    /**
     * @return Use the request and produce the document summary result.
     */
    virtual search::engine::DocsumReply::UP getDocsums(const search::engine::DocsumRequest & request) = 0;

    virtual search::engine::SearchReply::UP match(
            const ISearchHandler::SP &self,
            const search::engine::SearchRequest &req,
            vespalib::ThreadBundle &threadBundle) const = 0;
protected:
    ISearchHandler() = default;
};

} // namespace proton

