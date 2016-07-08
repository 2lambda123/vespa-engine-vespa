// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "indexproperties.h"
#include "properties.h"
#include <limits>

namespace search {
namespace fef {
namespace indexproperties {

namespace {

vespalib::string
lookupString(const Properties &props, const vespalib::string &name,
             const vespalib::string &defaultValue)
{
    Property p = props.lookup(name);
    if (p.found()) {
        return p.get();
    }
    return defaultValue;
}

std::vector<vespalib::string>
lookupStringVector(const Properties &props, const vespalib::string &name,
                   const std::vector<vespalib::string> &defaultValue)
{
    Property p = props.lookup(name);
    if (p.found()) {
        std::vector<vespalib::string> retval;
        for (uint32_t i = 0; i < p.size(); ++i) {
            retval.push_back(p.getAt(i));
        }
        return retval;
    }
    return defaultValue;
}

double
lookupDouble(const Properties &props, const vespalib::string &name, double defaultValue)
{
    Property p = props.lookup(name);
    if (p.found()) {
        return strtod(p.get().c_str(), NULL);
    }
    return defaultValue;
}

uint32_t
lookupUint32(const Properties &props, const vespalib::string &name, uint32_t defaultValue)
{
    Property p = props.lookup(name);
    if (p.found()) {
        return atoi(p.get().c_str());
    }
    return defaultValue;
}

bool
lookupBool(const Properties &props, const vespalib::string &name, bool defaultValue)
{
    Property p = props.lookup(name);
    if (p.found()) {
        return (p.get() == "true");
    }
    return defaultValue;
}

bool
checkIfTrue(const Properties &props, const vespalib::string &name,
            const vespalib::string &defaultValue)
{
    return (props.lookup(name).get(defaultValue) == "true");
}

}

namespace rank {

const vespalib::string FirstPhase::NAME("vespa.rank.firstphase");
const vespalib::string FirstPhase::DEFAULT_VALUE("nativeRank");

vespalib::string
FirstPhase::lookup(const Properties &props)
{
    return lookupString(props, NAME, DEFAULT_VALUE);
}

const vespalib::string SecondPhase::NAME("vespa.rank.secondphase");
const vespalib::string SecondPhase::DEFAULT_VALUE("");

vespalib::string
SecondPhase::lookup(const Properties &props)
{
    return lookupString(props, NAME, DEFAULT_VALUE);
}

} // namespace rank

namespace summary {

const vespalib::string Feature::NAME("vespa.summary.feature");
const std::vector<vespalib::string> Feature::DEFAULT_VALUE;

std::vector<vespalib::string>
Feature::lookup(const Properties &props)
{
    return lookupStringVector(props, NAME, DEFAULT_VALUE);
}

} // namespace summary

namespace dump {

const vespalib::string Feature::NAME("vespa.dump.feature");
const std::vector<vespalib::string> Feature::DEFAULT_VALUE;

std::vector<vespalib::string>
Feature::lookup(const Properties &props)
{
    return lookupStringVector(props, NAME, DEFAULT_VALUE);
}

const vespalib::string IgnoreDefaultFeatures::NAME("vespa.dump.ignoredefaultfeatures");
const vespalib::string IgnoreDefaultFeatures::DEFAULT_VALUE("false");

bool
IgnoreDefaultFeatures::check(const Properties &props)
{
    return checkIfTrue(props, NAME, DEFAULT_VALUE);
}

} // namespace dump

namespace matching {

const vespalib::string TermwiseLimit::NAME("vespa.matching.termwise_limit");
const double TermwiseLimit::DEFAULT_VALUE(1.0);

double
TermwiseLimit::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

const vespalib::string NumThreadsPerSearch::NAME("vespa.matching.numthreadspersearch");
const uint32_t NumThreadsPerSearch::DEFAULT_VALUE(std::numeric_limits<uint32_t>::max());

uint32_t
NumThreadsPerSearch::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string NumSearchPartitions::NAME("vespa.matching.numsearchpartitions");
const uint32_t NumSearchPartitions::DEFAULT_VALUE(1);

uint32_t
NumSearchPartitions::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string MinHitsPerThread::NAME("vespa.matching.minhitsperthread");
const uint32_t MinHitsPerThread::DEFAULT_VALUE(0);

uint32_t
MinHitsPerThread::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}


} // namespace matching

namespace matchphase {

const vespalib::string DegradationAttribute::NAME("vespa.matchphase.degradation.attribute");
const vespalib::string DegradationAttribute::DEFAULT_VALUE("");

const vespalib::string DegradationAscendingOrder::NAME("vespa.matchphase.degradation.ascendingorder");
const bool DegradationAscendingOrder::DEFAULT_VALUE(false);

const vespalib::string DegradationMaxHits::NAME("vespa.matchphase.degradation.maxhits");
const uint32_t DegradationMaxHits::DEFAULT_VALUE(0);

const vespalib::string DegradationSamplePercentage::NAME("vespa.matchphase.degradation.samplepercentage");
const double DegradationSamplePercentage::DEFAULT_VALUE(0.2);

const vespalib::string DegradationMaxFilterCoverage::NAME("vespa.matchphase.degradation.maxfiltercoverage");
const double DegradationMaxFilterCoverage::DEFAULT_VALUE(1.0);

const vespalib::string DegradationPostFilterMultiplier::NAME("vespa.matchphase.degradation.postfiltermultiplier");
const double DegradationPostFilterMultiplier::DEFAULT_VALUE(1.0);

const vespalib::string DiversityAttribute::NAME("vespa.matchphase.diversity.attribute");
const vespalib::string DiversityAttribute::DEFAULT_VALUE("");

const vespalib::string DiversityMinGroups::NAME("vespa.matchphase.diversity.mingroups");
const uint32_t DiversityMinGroups::DEFAULT_VALUE(1);

const vespalib::string DiversityCutoffFactor::NAME("vespa.matchphase.diversity.cutoff.factor");
const double DiversityCutoffFactor::DEFAULT_VALUE(10.0);

const vespalib::string DiversityCutoffStrategy::NAME("vespa.matchphase.diversity.cutoff.strategy");
const vespalib::string DiversityCutoffStrategy::DEFAULT_VALUE("loose");

vespalib::string
DegradationAttribute::lookup(const Properties &props)
{
    return lookupString(props, NAME, DEFAULT_VALUE);
}

bool
DegradationAscendingOrder::lookup(const Properties &props)
{
    return lookupBool(props, NAME, DEFAULT_VALUE);
}

uint32_t
DegradationMaxHits::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

double
DegradationSamplePercentage::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

double
DegradationMaxFilterCoverage::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

double
DegradationPostFilterMultiplier::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

vespalib::string
DiversityAttribute::lookup(const Properties &props)
{
    return lookupString(props, NAME, DEFAULT_VALUE);
}

uint32_t
DiversityMinGroups::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

double
DiversityCutoffFactor::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

vespalib::string
DiversityCutoffStrategy::lookup(const Properties &props)
{
    return lookupString(props, NAME, DEFAULT_VALUE);
}


}

namespace hitcollector {

const vespalib::string HeapSize::NAME("vespa.hitcollector.heapsize");
const uint32_t HeapSize::DEFAULT_VALUE(100);

uint32_t
HeapSize::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string ArraySize::NAME("vespa.hitcollector.arraysize");
const uint32_t ArraySize::DEFAULT_VALUE(10000);

uint32_t
ArraySize::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string EstimatePoint::NAME("vespa.hitcollector.estimatepoint");
const uint32_t EstimatePoint::DEFAULT_VALUE(0xffffffff);

uint32_t
EstimatePoint::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string EstimateLimit::NAME("vespa.hitcollector.estimatelimit");
const uint32_t EstimateLimit::DEFAULT_VALUE(0xffffffff);

uint32_t
EstimateLimit::lookup(const Properties &props)
{
    return lookupUint32(props, NAME, DEFAULT_VALUE);
}

const vespalib::string RankScoreDropLimit::NAME("vespa.hitcollector.rankscoredroplimit");
const feature_t RankScoreDropLimit::DEFAULT_VALUE(-std::numeric_limits<feature_t>::quiet_NaN());

feature_t
RankScoreDropLimit::lookup(const Properties &props)
{
    return lookupDouble(props, NAME, DEFAULT_VALUE);
}

} // namspace hitcollector


const vespalib::string FieldWeight::BASE_NAME("vespa.fieldweight.");
const uint32_t FieldWeight::DEFAULT_VALUE(100);

uint32_t
FieldWeight::lookup(const Properties &props, const vespalib::string &fieldName)
{
    return lookupUint32(props, BASE_NAME + fieldName, DEFAULT_VALUE);
}


const vespalib::string IsFilterField::BASE_NAME("vespa.isfilterfield.");
const vespalib::string IsFilterField::DEFAULT_VALUE("false");

void
IsFilterField::set(Properties &props, const vespalib::string &fieldName)
{
    props.add(BASE_NAME + fieldName, "true");
}

bool
IsFilterField::check(const Properties &props, const vespalib::string &fieldName)
{
    return checkIfTrue(props, BASE_NAME + fieldName, DEFAULT_VALUE);
}


namespace type {

const vespalib::string Attribute::BASE_NAME("vespa.type.attribute.");
const vespalib::string Attribute::DEFAULT_VALUE("");

vespalib::string
Attribute::lookup(const Properties &props, const vespalib::string &attributeName)
{
    return lookupString(props, BASE_NAME + attributeName, DEFAULT_VALUE);
}

void
Attribute::set(Properties &props, const vespalib::string &attributeName, const vespalib::string &type)
{
    props.add(BASE_NAME + attributeName, type);
}

const vespalib::string QueryFeature::BASE_NAME("vespa.type.query.");
const vespalib::string QueryFeature::DEFAULT_VALUE("");

vespalib::string
QueryFeature::lookup(const Properties &props, const vespalib::string &queryFeatureName)
{
    return lookupString(props, BASE_NAME + queryFeatureName, DEFAULT_VALUE);
}

void
QueryFeature::set(Properties &props, const vespalib::string &queryFeatureName, const vespalib::string &type)
{
    props.add(BASE_NAME + queryFeatureName, type);
}

} // namespace type

} // namespace indexproperties
} // namespace fef
} // namespace search
