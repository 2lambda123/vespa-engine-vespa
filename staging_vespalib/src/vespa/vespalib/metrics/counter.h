// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <memory>
#include "metric_identifier.h"
#include "point.h"

namespace vespalib {
namespace metrics {

class MetricsManager;
class CounterAggregator;


class Counter {
    std::shared_ptr<MetricsManager> _manager;
    MetricName _id;
public:
    Counter() : _manager(), _id(0) {}
    Counter(const Counter&) = delete;
    Counter(Counter &&other) = default;
    Counter& operator= (const Counter &) = delete;
    Counter& operator= (Counter &&other) = default;
    Counter(std::shared_ptr<MetricsManager> m, MetricName id)
        : _manager(std::move(m)), _id(id)
    {}

    void add() const { add(1, Point::empty); }
    void add(Point p) { add(1, p); }
    void add(size_t count, Point p = Point::empty) const;

    struct Increment {
        MetricIdentifier idx;
        size_t value;
        Increment() = delete;
        Increment(MetricIdentifier id, size_t v) : idx(id), value(v) {}
    };

    typedef CounterAggregator aggregator_type;
    typedef Increment sample_type;
};

} // namespace vespalib::metrics
} // namespace vespalib
