// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "ownership_transfer_safe_time_point_calculator.h"
#include <thread>

namespace storage {
namespace distributor {

OwnershipTransferSafeTimePointCalculator::TimePoint
OwnershipTransferSafeTimePointCalculator::safeTimePoint(
        TimePoint now) const
{
    if (_max_cluster_clock_skew.count() == 0) {
        return TimePoint{};
    }
    // Rationale: distributors always generate time stamps by taking
    // the current second and adding a synthetic microsecond counter.
    // This means that if we assume a 1 second max skew and one node
    // operates with t=5.95, another node may be operating with t=4.95.
    // If they both receive their first operation within that second,
    // they will be 5.000001 and 4.000001, respectively. If we just
    // waited for max clock skew number of seconds, we would end up
    // with t=5.95 -> t=6.95 and 4.95 -> 5.95. In the second node's case,
    // the first timestamp it would generate would be 5.000001, which
    // collides with the one already generated by the the other node!
    // To avoid this, we round upwards to the nearest whole second before
    // adding the max skew. This prevents generating time stamps within
    // the same whole second as another distributor already has done for
    // any of the buckets a node now owns.
    auto now_sec = std::chrono::duration_cast<std::chrono::seconds>(
            now.time_since_epoch());
    return TimePoint(now_sec + std::chrono::seconds(1) + _max_cluster_clock_skew);
}

}
}
