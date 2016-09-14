// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/searchcore/proton/flushengine/iflushstrategy.h>
#include <vespa/vespalib/util/sync.h>
#include <vespa/vespalib/stllike/string.h>
#include <map>

namespace proton {

/*
 * Class implementing "flush" everything strategy.  Targets are just
 * sorted on age.
 */
class FlushAllStrategy : public IFlushStrategy
{
public:
    FlushAllStrategy();

    FlushContext::List
    getFlushTargets(const FlushContext::List &targetList,
                    const flushengine::TlsStatsMap &) const override;
};

} // namespace proton
