// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "simple_metrics.h"

namespace vespalib {
namespace metrics {

// internal
struct MergedCounter {
    unsigned int idx;
    size_t count;
    MergedCounter(unsigned int idx);
    void merge(const CounterIncrement &other);
    void merge(const MergedCounter &other);
};

// internal
struct MergedGauge {
    unsigned int idx;
    size_t observedCount;
    double sumValue;
    double minValue;
    double maxValue;
    double lastValue;

    MergedGauge(unsigned int idx);

    void merge(const GaugeMeasurement &other);
    void merge(const MergedGauge &other);
};

// internal
struct CurrentSamples {
    std::vector<CounterIncrement> counterIncrements;
    std::vector<GaugeMeasurement> gaugeMeasurements;
};

// internal
struct Bucket {
    clock::time_point startTime;
    clock::time_point endedTime;
    std::vector<MergedCounter> counters;
    std::vector<MergedGauge> gauges;

    void merge(const CurrentSamples &other);
    void merge(const Bucket &other);

    Bucket(clock::time_point start, clock::time_point ended)
        : startTime(start),
          endedTime(ended),
          counters(),
          gauges()
    {}
    ~Bucket() {}
};

extern void swap(CurrentSamples& a, CurrentSamples& b);
extern void swap(Bucket& a, Bucket& b);

} // namespace vespalib::metrics
} // namespace vespalib
