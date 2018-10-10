// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class metrics::ValueMetric
 * @ingroup metrics
 *
 * @brief Creates a metric measuring any value.
 *
 * A value metric have the following properties:
 *   - Logs the average as a value event. (It is not strictly increasing)
 *   - When summing average metrics together, the sum becomes the average of
 *     all values added to both.
 */

#pragma once

#include "valuemetricvalues.h"
#include "metric.h"
#include <cmath>

namespace metrics {

struct AbstractValueMetric : public Metric {
    bool visit(MetricVisitor& visitor, bool tagAsAutoGenerated = false) const override {
        return visitor.visitValueMetric(*this, tagAsAutoGenerated);
    }
    virtual MetricValueClass::UP getValues() const = 0;
    virtual bool inUse(const MetricValueClass& v) const = 0;
    virtual bool summedAverage() const = 0;

protected:
    AbstractValueMetric(const String& name, Tags dimensions,
                        const String& description, MetricSet* owner)
        : Metric(name, std::move(dimensions), description, owner) {}

    AbstractValueMetric(const AbstractValueMetric& other, MetricSet* owner)
        : Metric(other, owner) {}

    void logWarning(const char* msg, const char *op) const;
    void logNonFiniteValueWarning() const;
    void sendLogEvent(Metric::String name, double value) const;
};

template<typename AvgVal, typename TotVal, bool SumOnAdd>
class ValueMetric : public AbstractValueMetric {
    using String = Metric::String; // Redefine so template finds it easy
    using Values = ValueMetricValues<AvgVal, TotVal>;

    MetricValueSet<Values> _values;

    enum Flag {
        SUMMED_AVERAGE = 2, UNSET_ON_ZERO_VALUE = 4, LOG_IF_UNSET = 8
    };

    bool summedAverage() const override { return _values.hasFlag(SUMMED_AVERAGE); }

    bool unsetOnZeroValue() const { return _values.hasFlag(UNSET_ON_ZERO_VALUE); }

    bool logIfUnset() const { return _values.hasFlag(LOG_IF_UNSET); }

    void add(const Values &values, bool sumOnAdd);

    void dec(const Values &values);

    void addValueWithCount(AvgVal avg, TotVal tot, uint32_t count, AvgVal min, AvgVal max);
    void addValueWithCount(AvgVal avg, TotVal tot, uint32_t count) {
        addValueWithCount(avg, tot, count, avg, avg);
    }

    // Finite number (not infinity/NaN) check using type trait tag dispatch.
    // 2nd param is instance of std::true_type iff AvgVal is floating point.
    bool checkFinite(AvgVal v, std::true_type) {
        if (!std::isfinite(v)) {
            logNonFiniteValueWarning();
            return false;
        }
        return true;
    }

    bool checkFinite(AvgVal, std::false_type) { return true; }

public:
    ValueMetric(const ValueMetric<AvgVal, TotVal, SumOnAdd> &,
                CopyType, MetricSet *owner);

    ValueMetric(const String &name, Tags dimensions,
                const String &description, MetricSet *owner = 0);

    ~ValueMetric();

    MetricValueClass::UP getValues() const override {
        return MetricValueClass::UP(new Values(_values.getValues()));
    }

    void unsetOnZeroValue() { _values.setFlag(UNSET_ON_ZERO_VALUE); }

    void logOnlyIfSet() { _values.removeFlag(LOG_IF_UNSET); }

    ValueMetric *clone(std::vector<Metric::UP> &, CopyType type, MetricSet *owner,
                       bool /*includeUnused*/) const override {
        return new ValueMetric<AvgVal,TotVal,SumOnAdd>(*this, type, owner);
    }

    ValueMetric & operator+=(const ValueMetric &);

    friend ValueMetric operator+(const ValueMetric & a, const ValueMetric & b) {
        ValueMetric t(a); t += b; return t;
    }

    void addAvgValueWithCount(AvgVal avg, uint32_t count) {
        if (count > 0) {
            addValueWithCount(avg, avg * count, count);
        }
    }
    void addTotalValueWithCount(TotVal tot, uint32_t count) {
        if (count > 0) {
            addValueWithCount(tot / count, tot, count);
        }
    }
    void addValueBatch(AvgVal avg, uint32_t count, AvgVal min, AvgVal max) {
        if (count > 0) {
            addValueWithCount(avg, avg * count, count, min, max);
        }
    }
    virtual void addValue(AvgVal avg) { addAvgValueWithCount(avg, 1); }
    virtual void set(AvgVal avg) { addValue(avg); }
    virtual void inc(AvgVal val = 1);
    virtual void dec(AvgVal val = 1);

    double getAverage() const;
    AvgVal getMinimum() const { return _values.getValues()._min; }
    AvgVal getMaximum() const { return _values.getValues()._max; }
    AvgVal getCount() const { return _values.getValues()._count; }
    TotVal getTotal() const { return _values.getValues()._total; }

    AvgVal getLast() const { return _values.getValues()._last; }

    void reset() override { _values.reset(); }
    bool logEvent(const String& fullName) const override;

    void print(std::ostream&, bool verbose,
               const std::string& indent, uint64_t secondsPassed) const override;

    int64_t getLongValue(stringref id) const override;
    double getDoubleValue(stringref id) const override;

    bool inUse(const MetricValueClass& v) const override {
        const Values& values(static_cast<const Values&>(v));
        return (values._total != 0
                || (values._count != 0 && !unsetOnZeroValue()));
    }
    bool used() const override { return inUse(_values.getValues()); }
    void addMemoryUsage(MemoryConsumption&) const override;
    void printDebug(std::ostream&, const std::string& indent) const override;
    void addToPart(Metric&) const override;
    void addToSnapshot(Metric&, std::vector<Metric::UP> &) const override;
};

typedef ValueMetric<double, double, true> DoubleValueMetric;
typedef ValueMetric<double, double, false> DoubleAverageMetric;
typedef ValueMetric<int64_t, int64_t, true> LongValueMetric;
typedef ValueMetric<int64_t, int64_t, false> LongAverageMetric;

} // metrics

