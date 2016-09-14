// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/searchcore/proton/flushengine/iflushstrategy.h>
#include <vespa/vespalib/util/sync.h>

namespace proton {

class SimpleFlush : public IFlushStrategy
{
private:
    class CompareTarget {
    public:
        bool operator () (const FlushContext::SP &lhs, const FlushContext::SP &rhs) const {
            return compare(*lhs->getTarget(), *rhs->getTarget());
        }
    private:
        bool compare(const IFlushTarget & lhs, const IFlushTarget & rhs) const;
    };
public:
    SimpleFlush();

    // Implements IFlushStrategy
    virtual FlushContext::List getFlushTargets(const FlushContext::List &targetList,
                                               const flushengine::TlsStatsMap &tlsStatsMap) const;

};

} // namespace proton

