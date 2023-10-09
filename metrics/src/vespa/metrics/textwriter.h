// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "metric.h"
#include <vespa/vespalib/util/time.h>
#include <regex>
#include <optional>

namespace metrics {

class TextWriter : public MetricVisitor {
    vespalib::duration _period;
    std::ostream& _out;
    std::vector<std::string> _path;
    std::optional<std::regex> _regex;
    bool _verbose;

public:
    TextWriter(std::ostream& out, vespalib::duration period,
               const std::string& regex, bool verbose);
    ~TextWriter() override;

    bool visitSnapshot(const MetricSnapshot&) override;
    void doneVisitingSnapshot(const MetricSnapshot&) override;
    bool visitMetricSet(const MetricSet& set, bool) override;
    void doneVisitingMetricSet(const MetricSet&) override;
    bool visitCountMetric(const AbstractCountMetric&, bool autoGenerated) override;
    bool visitValueMetric(const AbstractValueMetric&, bool autoGenerated) override;

private:
    bool writeCommon(const Metric& m);
};

}

