// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <memory>
#include "metric_identifier.h"
#include "point.h"

namespace vespalib {
namespace metrics {

class MetricsManager;
class GaugeAggregator;

struct GaugeMeasurement {
    MetricIdentifier idx;
    double value;
    GaugeMeasurement() = delete;
    GaugeMeasurement(MetricIdentifier id, double v) : idx(id), value(v) {}
};

class Gauge {
private:
    std::shared_ptr<MetricsManager> _manager;
    MetricIdentifier _idx;
    MetricIdentifier ident() const { return _idx; }
public:
    Gauge(std::shared_ptr<MetricsManager> m, MetricIdentifier id)
        : _manager(std::move(m)), _idx(id)
    {}

    void sample(double value) const;
    void sample(double value, Point p) const;

    typedef GaugeAggregator aggregator_type;
    typedef GaugeMeasurement sample_type;
};

} // namespace vespalib::metrics
} // namespace vespalib
