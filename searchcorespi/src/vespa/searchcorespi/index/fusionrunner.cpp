// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".searchcorespi.index.fusionrunner");

#include "fusionrunner.h"
#include "eventlogger.h"
#include "fusionspec.h"
#include <vespa/searchlib/common/serialnumfileheadercontext.h>
#include <vespa/searchlib/attribute/fixedsourceselector.h>
#include <vespa/searchlib/queryeval/isourceselector.h>
#include <vespa/searchlib/util/dirtraverse.h>
#include <vespa/vespalib/util/jsonwriter.h>

using search::FixedSourceSelector;
using search::TuneFileAttributes;
using search::TuneFileIndexing;
using search::common::FileHeaderContext;
using search::common::SerialNumFileHeaderContext;
using search::index::Schema;
using search::queryeval::ISourceSelector;
using search::diskindex::SelectorArray;
using search::SerialNum;
using std::vector;
using vespalib::string;
using vespalib::JSONStringer;

namespace searchcorespi {
namespace index {

FusionRunner::FusionRunner(const string &base_dir,
                           const Schema &schema,
                           const TuneFileAttributes &tuneFileAttributes,
                           const FileHeaderContext &fileHeaderContext)
    : _diskLayout(base_dir),
      _schema(schema),
      _tuneFileAttributes(tuneFileAttributes),
      _fileHeaderContext(fileHeaderContext)
{ }

FusionRunner::~FusionRunner() {
}

namespace {

void readSelectorArray(const string &selector_name, SelectorArray &selector_array,
                       const vector<uint8_t> &id_map, uint32_t base_id) {
    FixedSourceSelector::UP selector =
        FixedSourceSelector::load(selector_name);
    if (base_id != selector->getBaseId()) {
        selector = selector->cloneAndSubtract("tmp_for_fusion", base_id - selector->getBaseId());
    }

    const uint32_t num_docs = selector->getDocIdLimit();
    selector_array.reserve(num_docs);
    auto it = selector->createIterator();
    for (uint32_t i = 0; i < num_docs; ++i) {
        search::queryeval::Source source = it->getSource(i);
        assert(source < id_map.size());
        selector_array.push_back(id_map[source]);
    }
}

bool
writeFusionSelector(const IndexDiskLayout &diskLayout, uint32_t fusion_id,
                    uint32_t highest_doc_id,
                    const TuneFileAttributes &tuneFileAttributes,
                    const FileHeaderContext &fileHeaderContext)
{
    const search::queryeval::Source default_source = 0;
    FixedSourceSelector fusion_selector(default_source, "fusion_selector");
    fusion_selector.setSource(highest_doc_id, default_source);
    fusion_selector.setBaseId(fusion_id);
    string selector_name = IndexDiskLayout::getSelectorFileName(diskLayout.getFusionDir(fusion_id));
    if (!fusion_selector.extractSaveInfo(selector_name)->save(tuneFileAttributes, fileHeaderContext)) {
        LOG(warning, "Unable to write source selector data for fusion.%u.", fusion_id);
        return false;
    }
    return true;
}
}  // namespace

uint32_t
FusionRunner::fuse(const FusionSpec &fusion_spec,
                   SerialNum lastSerialNum,
                   IIndexMaintainerOperations &operations)
{
    const vector<uint32_t> &ids = fusion_spec.flush_ids;
    if (ids.empty()) {
        return 0;
    }
    const uint32_t fusion_id = ids.back();
    const string fusion_dir = _diskLayout.getFusionDir(fusion_id);

    vector<string> sources;
    vector<uint8_t> id_map(fusion_id + 1);
    if (fusion_spec.last_fusion_id != 0) {
        id_map[0] = sources.size();
        sources.push_back(_diskLayout.getFusionDir(fusion_spec.last_fusion_id));
    }
    for (size_t i = 0; i < ids.size(); ++i) {
        id_map[ids[i] - fusion_spec.last_fusion_id] = sources.size();
        sources.push_back(_diskLayout.getFlushDir(ids[i]));
    }

    if (LOG_WOULD_LOG(event)) {
        EventLogger::diskFusionStart(sources, fusion_dir);
    }
    FastOS_Time timer;
    timer.SetNow();

    const string selector_name = IndexDiskLayout::getSelectorFileName(_diskLayout.getFlushDir(fusion_id));
    SelectorArray selector_array;
    readSelectorArray(selector_name, selector_array, id_map, fusion_spec.last_fusion_id);

    if (!operations.runFusion(_schema, fusion_dir, sources, selector_array, lastSerialNum)) {
        return 0;
    }

    const uint32_t highest_doc_id = selector_array.size() - 1;
    SerialNumFileHeaderContext fileHeaderContext(_fileHeaderContext, lastSerialNum);
    if (!writeFusionSelector(_diskLayout, fusion_id, highest_doc_id, _tuneFileAttributes, fileHeaderContext)) {
        return 0;
    }

    if (LOG_WOULD_LOG(event)) {
        EventLogger::diskFusionComplete(fusion_dir, (int64_t)timer.MilliSecsToNow());
    }
    return fusion_id;
}

}  // namespace index
}  // namespace searchcorespi
