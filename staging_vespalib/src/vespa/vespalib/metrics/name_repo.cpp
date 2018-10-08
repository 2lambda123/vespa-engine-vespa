// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "name_repo.h"

#include <vespa/log/log.h>
LOG_SETUP(".vespalib.metrics.name_repo");

namespace vespalib {
namespace metrics {

MetricName
NameRepo::metric(const vespalib::string &name)
{
    size_t id = _metricNames.resolve(name);
    LOG(debug, "metric name %s -> %zu", name.c_str(), id);
    return MetricName(id);
}

Dimension
NameRepo::dimension(const vespalib::string &name)
{
    size_t id = _dimensionNames.resolve(name);
    LOG(debug, "dimension name %s -> %zu", name.c_str(), id);
    return Dimension(id);
}

Label
NameRepo::label(const vespalib::string &value)
{
    size_t id = _labelValues.resolve(value);
    LOG(debug, "label value %s -> %zu", value.c_str(), id);
    return Label(id);
}

const vespalib::string&
NameRepo::metricName(MetricName metric)
{
    return _metricNames.lookup(metric.id());
}

const vespalib::string&
NameRepo::dimensionName(Dimension dim)
{
    return _dimensionNames.lookup(dim.id());
}

const vespalib::string&
NameRepo::labelValue(Label l)
{
    return _labelValues.lookup(l.id());
}


const PointMap::BackingMap&
NameRepo::pointMap(Point from)
{
    const PointMap &map = _pointMaps.lookup(from.id());
    return map.backingMap();
}

Point
NameRepo::pointFrom(PointMap::BackingMap map)
{
    size_t id = _pointMaps.resolve(PointMap(std::move(map)));
    return Point(id);
}


NameRepo NameRepo::instance;


} // namespace vespalib::metrics
} // namespace vespalib
