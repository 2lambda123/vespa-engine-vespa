// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "simple_metrics_manager.h"

#include <vespa/log/log.h>
LOG_SETUP(".vespalib.metrics.simple_metrics_manager");

namespace vespalib {
namespace metrics {

using Guard = std::lock_guard<std::mutex>;

SimpleMetricsManager::SimpleMetricsManager(const SimpleManagerConfig &config)
    : _metricNames(),
      _dimensionNames(),
      _labelValues(),
      _pointMaps(),
      _currentBucket(),
      _startTime(now_stamp()),
      _curTime(_startTime),
      _buckets(),
      _firstBucket(0),
      _maxBuckets(config.sliding_window_seconds),
      _stopFlag(false),
      _collectorThread(doCollectLoop, this)
{
    if (_maxBuckets < 1) _maxBuckets = 1;
    Point empty = pointFrom(PointMapBacking());
    assert(empty.id() == 0);
}

SimpleMetricsManager::~SimpleMetricsManager()
{
    _stopFlag = true;
    _collectorThread.join();
}


std::shared_ptr<MetricsManager>
SimpleMetricsManager::create(const SimpleManagerConfig &config)
{
    return std::shared_ptr<MetricsManager>(
        new SimpleMetricsManager(config));
}

Counter
SimpleMetricsManager::counter(const vespalib::string &name)
{
    size_t id = _metricNames.resolve(name);
    _metricTypes.check(id, name, MetricTypes::COUNTER);
    LOG(debug, "metric name %s -> %zd", name.c_str(), id);
    return Counter(shared_from_this(), MetricIdentifier(id));
}

Gauge
SimpleMetricsManager::gauge(const vespalib::string &name)
{
    size_t id = _metricNames.resolve(name);
    _metricTypes.check(id, name, MetricTypes::GAUGE);
    LOG(debug, "metric name %s -> %zd", name.c_str(), id);
    return Gauge(shared_from_this(), MetricIdentifier(id));
}

Bucket
SimpleMetricsManager::mergeBuckets()
{
    Guard bucketsGuard(_bucketsLock);
    if (_buckets.size() > 0) {
        InternalTimeStamp startTime = _buckets[_firstBucket].startTime;
        Bucket merger(startTime, startTime);
        for (size_t i = 0; i < _buckets.size(); i++) {
            size_t off = (_firstBucket + i) % _buckets.size();
            merger.merge(_buckets[off]);
        }
        return merger;
    }
    // no data
    return Bucket(_startTime, _curTime);
}

Snapshot
SimpleMetricsManager::snapshot()
{
    Bucket merged = mergeBuckets();
    std::vector<PointSnapshot> points;

    std::chrono::microseconds s = since_epoch(merged.startTime);
    std::chrono::microseconds e = since_epoch(merged.endTime);
    const double micro = 0.000001;
    Snapshot snap(s.count() * micro, e.count() * micro);
    {
        Guard guard(_pointMaps.lock);
        for (auto entry : _pointMaps.vec) {
             const PointMapBacking &map = entry->first.backing();
             PointSnapshot point;
             for (const PointMapBacking::value_type &kv : map) {
                 point.dimensions.emplace_back(nameFor(kv.first), valueFor(kv.second));
             }
             snap.add(point);
        }
    }
    for (const CounterAggregator& entry : merged.counters) {
        size_t ni = entry.idx.name_idx;
        size_t pi = entry.idx.point_idx;
        const vespalib::string &name = _metricNames.lookup(ni);
        CounterSnapshot val(name, snap.points()[pi], entry);
        snap.add(val);
    }
    for (const GaugeAggregator& entry : merged.gauges) {
        size_t ni = entry.idx.name_idx;
        size_t pi = entry.idx.point_idx;
        const vespalib::string &name = _metricNames.lookup(ni);
        GaugeSnapshot val(name, snap.points()[pi], entry);
        snap.add(val);
    }
    return snap;
}

void
SimpleMetricsManager::doCollectLoop(SimpleMetricsManager *me)
{
    const std::chrono::milliseconds jiffy{20};
    const std::chrono::seconds oneSec{1};
    while (!me->_stopFlag) {
        std::this_thread::sleep_for(jiffy);
        InternalTimeStamp now = now_stamp();
        InternalTimeStamp::duration elapsed = now - me->_curTime;
        if (elapsed >= oneSec) {
            me->collectCurrentBucket();
        }
    }
}

void
SimpleMetricsManager::collectCurrentBucket()
{
    InternalTimeStamp prev = _curTime;
    InternalTimeStamp curr = now_stamp();

    CurrentSamples samples;
    {
        Guard guard(_currentBucket.lock);
        swap(samples, _currentBucket);
    }

    Bucket merger(prev, curr);
    Guard guard(_bucketsLock);
    if (_buckets.size() < _maxBuckets) {
        _buckets.push_back(merger);
        _buckets.back().merge(samples);
    } else {
        merger.merge(samples);
        swap(_buckets[_firstBucket], merger);
        _firstBucket = (_firstBucket + 1) % _buckets.size();
    }
    _curTime = curr;
}

Dimension
SimpleMetricsManager::dimension(const vespalib::string &name)
{
    size_t id = _dimensionNames.resolve(name);
    LOG(debug, "dimension name %s -> %zd", name.c_str(), id);
    return Dimension(id);
}

Label
SimpleMetricsManager::label(const vespalib::string &value)
{
    size_t id = _labelValues.resolve(value);
    LOG(debug, "label value %s -> %zd", value.c_str(), id);
    return Label(id);
}

PointBuilder
SimpleMetricsManager::pointBuilder(Point from)
{
    Guard guard(_pointMaps.lock);
    const PointMap &map = _pointMaps.vec[from.id()]->first;
    return PointBuilder(shared_from_this(), map.backing());
}

Point
SimpleMetricsManager::pointFrom(PointMapBacking &&map)
{
    Guard guard(_pointMaps.lock);
    size_t nextId = _pointMaps.vec.size();
    auto iter_check = _pointMaps.map.emplace(std::move(map), nextId);
    if (iter_check.second) {
        LOG(debug, "new point map -> %zd / %zd", nextId, iter_check.first->second);
        _pointMaps.vec.push_back(iter_check.first);
    } else {
        LOG(debug, "found point map -> %zd", iter_check.first->second);
    }
    return Point(iter_check.first->second);
}

} // namespace vespalib::metrics
} // namespace vespalib
