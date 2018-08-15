// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "sample_attribute_usage_job.h"
#include <vespa/searchcore/proton/attribute/i_attribute_manager.h>
#include <vespa/searchcore/proton/attribute/attribute_usage_filter.h>
#include <vespa/searchcore/proton/attribute/attribute_usage_sampler_context.h>
#include <vespa/searchcore/proton/attribute/attribute_usage_sampler_functor.h>

namespace proton {

SampleAttributeUsageJob::
SampleAttributeUsageJob(IAttributeManagerSP readyAttributeManager,
                        IAttributeManagerSP notReadyAttributeManager,
                        AttributeUsageFilter &attributeUsageFilter,
                        const vespalib::string &docTypeName,
                        double interval)
    : IMaintenanceJob("sample_attribute_usage." + docTypeName, 0.0, interval),
      _readyAttributeManager(readyAttributeManager),
      _notReadyAttributeManager(notReadyAttributeManager),
      _attributeUsageFilter(attributeUsageFilter)
{
}

SampleAttributeUsageJob::~SampleAttributeUsageJob() = default;

bool
SampleAttributeUsageJob::run()
{
    auto context = std::make_shared<AttributeUsageSamplerContext> (_attributeUsageFilter);
    _readyAttributeManager->asyncForEachAttribute(std::make_shared<AttributeUsageSamplerFunctor>(context, "ready"));
    _notReadyAttributeManager->asyncForEachAttribute(std::make_shared<AttributeUsageSamplerFunctor>(context, "notready"));
    return true;
}

} // namespace proton
