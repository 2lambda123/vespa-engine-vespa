// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".searchlib.docsummary.summaryfeaturesdfw");
#include <vespa/searchlib/common/featureset.h>
#include <vespa/searchlib/common/packets.h>
#include <vespa/searchsummary/docsummary/docsumformat.h>
#include "summaryfeaturesdfw.h"
#include <cmath>

namespace search {
namespace docsummary {


SummaryFeaturesDFW::SummaryFeaturesDFW() :
    _env(NULL)
{
}

SummaryFeaturesDFW::~SummaryFeaturesDFW()
{
}

void
SummaryFeaturesDFW::init(IDocsumEnvironment * env)
{
    _env = env;
}

static vespalib::string _G_cached("vespa.summaryFeatures.cached");
static vespalib::slime::Memory _M_cached("vespa.summaryFeatures.cached");

void
SummaryFeaturesDFW::insertField(uint32_t docid,
                                GeneralResult *,
                                GetDocsumsState *state,
                                ResType type,
                                vespalib::slime::Inserter &target)
{
    if (state->_summaryFeatures.get() == 0) {
        state->_callback.FillSummaryFeatures(state, _env);
        if (state->_summaryFeatures.get() == 0) { // still no summary features to write
            return;
        }
    }
    const FeatureSet::StringVector &names = state->_summaryFeatures->getNames();
    const feature_t *values = state->_summaryFeatures->getFeaturesByDocId(docid);
    if (type == RES_FEATUREDATA && values != NULL) {
        vespalib::slime::Cursor& obj = target.insertObject();
        for (uint32_t i = 0; i < names.size(); ++i) {
            vespalib::slime::Memory name(names[i].c_str(), names[i].size());
            obj.setDouble(name, values[i]);
        }
        if (state->_summaryFeaturesCached) {
            obj.setDouble(_M_cached, 1.0);
        } else {
            obj.setDouble(_M_cached, 0.0);
        }
        return;
    }
    vespalib::JSONStringer & json(state->_jsonStringer);
    if (values != NULL) {
        json.clear();
        json.beginObject();
        for (uint32_t i = 0; i < names.size(); ++i) {
            featureDump(json, names[i], values[i]);
        }
        json.appendKey(_G_cached);
        if (state->_summaryFeaturesCached) {
            json.appendDouble(1.0);
        } else {
            json.appendDouble(0.0);
        }
        json.endObject();
        vespalib::slime::Memory value(json.toString().c_str(),
                                      json.toString().size());
        if (type == RES_STRING || type == RES_LONG_STRING) {
            target.insertString(value);
        }
        if (type == RES_DATA || type == RES_LONG_DATA) {
            target.insertData(value);
        }
        json.clear();
    }
}

uint32_t
SummaryFeaturesDFW::WriteField(uint32_t docid,
                               GeneralResult * gres,
                               GetDocsumsState * state,
                               ResType type,
                               search::RawBuf * target)
{
    (void) gres;

    if (state->_summaryFeatures.get() == 0) {
        state->_callback.FillSummaryFeatures(state, _env);
        if (state->_summaryFeatures.get() == 0) { // still no summary features to write
            return DocsumFormat::addEmpty(type, *target);
        }
    }

    uint32_t written = 0;

    const FeatureSet::StringVector &names = state->_summaryFeatures->getNames();
    vespalib::JSONStringer & json(state->_jsonStringer);
    const feature_t *values = state->_summaryFeatures->getFeaturesByDocId(docid);
    if (values != NULL) {
        json.clear();
        json.beginObject();
        for (uint32_t i = 0; i < names.size(); ++i) {
            featureDump(json, names[i], values[i]);
        }
        json.appendKey(_G_cached);
        if (state->_summaryFeaturesCached) {
            json.appendDouble(1.0);
        } else {
            json.appendDouble(0.0);
        }
        json.endObject();

        written += writeString(json.toString(), type, target);
        json.clear();
    } else {
        written += DocsumFormat::addEmpty(type, *target);
    }

    return written;
}

void FeaturesDFW::featureDump(vespalib::JSONStringer & json, const vespalib::stringref & name, double feature)
{
    json.appendKey(name);
    if (std::isnan(feature) || std::isinf(feature)) {
        json.appendNull();
    } else {
        json.appendDouble(feature);
    }
}


uint32_t
SummaryFeaturesDFW::writeString(const vespalib::stringref & str, ResType type, search::RawBuf * target)
{
    switch (type) {
    case RES_STRING:
    case RES_DATA:
        return DocsumFormat::addShortData(*target, str.c_str(), str.size());
    case RES_FEATUREDATA:
    case RES_LONG_STRING:
    case RES_LONG_DATA:
        return DocsumFormat::addLongData(*target, str.c_str(), str.size());
    default:
        LOG(error, "unhandled type %u in writeString()", type);
        return DocsumFormat::addEmpty(type, *target);
    }
}

}
}
