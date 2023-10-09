// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "indexmanager.h"
#include "diskindexwrapper.h"
#include "memoryindexwrapper.h"
#include <vespa/searchcorespi/index/indexmaintainerconfig.h>
#include <vespa/searchlib/common/serialnumfileheadercontext.h>
#include <vespa/searchlib/diskindex/fusion.h>
#include <vespa/searchlib/index/schemautil.h>

using search::diskindex::Fusion;
using search::common::FileHeaderContext;
using search::common::SerialNumFileHeaderContext;
using search::index::Schema;
using search::index::SchemaUtil;
using search::IFlushToken;
using search::TuneFileIndexing;
using search::TuneFileIndexManager;
using search::TuneFileSearch;
using searchcorespi::index::IDiskIndex;
using search::diskindex::SelectorArray;
using searchcorespi::index::IndexMaintainerConfig;
using searchcorespi::index::WarmupConfig;
using searchcorespi::index::IndexMaintainerContext;
using searchcorespi::index::IMemoryIndex;
using searchcorespi::index::IThreadingService;

namespace proton::index {

IndexManager::MaintainerOperations::MaintainerOperations(const FileHeaderContext &fileHeaderContext,
                                                         const TuneFileIndexManager &tuneFileIndexManager,
                                                         size_t cacheSize,
                                                         IThreadingService &threadingService)
    : _cacheSize(cacheSize),
      _fileHeaderContext(fileHeaderContext),
      _tuneFileIndexing(tuneFileIndexManager._indexing),
      _tuneFileSearch(tuneFileIndexManager._search),
      _threadingService(threadingService)
{
}

IMemoryIndex::SP
IndexManager::MaintainerOperations::createMemoryIndex(const Schema& schema,
                                                      const IFieldLengthInspector& inspector,
                                                      SerialNum serialNum)
{
    return std::make_shared<MemoryIndexWrapper>(schema, inspector, _fileHeaderContext, _tuneFileIndexing,
                                                _threadingService, serialNum);
}

IDiskIndex::SP
IndexManager::MaintainerOperations::loadDiskIndex(const vespalib::string &indexDir)
{
    return std::make_shared<DiskIndexWrapper>(indexDir, _tuneFileSearch, _cacheSize);
}

IDiskIndex::SP
IndexManager::MaintainerOperations::reloadDiskIndex(const IDiskIndex &oldIndex)
{
    return std::make_shared<DiskIndexWrapper>(dynamic_cast<const DiskIndexWrapper &>(oldIndex),
                                              _tuneFileSearch, _cacheSize);
}

bool
IndexManager::MaintainerOperations::runFusion(const Schema &schema,
                                              const vespalib::string &outputDir,
                                              const std::vector<vespalib::string> &sources,
                                              const SelectorArray &selectorArray,
                                              SerialNum serialNum,
                                              std::shared_ptr<IFlushToken> flush_token)
{
    SerialNumFileHeaderContext fileHeaderContext(_fileHeaderContext, serialNum);
    Fusion fusion(schema, outputDir, sources, selectorArray,
                  _tuneFileIndexing, fileHeaderContext);
    return fusion.merge(_threadingService.shared(), std::move(flush_token));
}


IndexManager::IndexManager(const vespalib::string &baseDir,
                           const IndexConfig & indexConfig,
                           const Schema &schema,
                           SerialNum serialNum,
                           Reconfigurer &reconfigurer,
                           IThreadingService &threadingService,
                           vespalib::Executor & warmupExecutor,
                           const search::TuneFileIndexManager &tuneFileIndexManager,
                           const search::TuneFileAttributes &tuneFileAttributes,
                           const FileHeaderContext &fileHeaderContext) :
    _operations(fileHeaderContext, tuneFileIndexManager, indexConfig.cacheSize, threadingService),
    _maintainer(IndexMaintainerConfig(baseDir, indexConfig.warmup, indexConfig.maxFlushed, schema, serialNum, tuneFileAttributes),
                IndexMaintainerContext(threadingService, reconfigurer, fileHeaderContext, warmupExecutor),
                _operations)
{
}

IndexManager::~IndexManager() = default;

void
IndexManager::compactLidSpace(uint32_t lidLimit, SerialNum serialNum)
{
    _maintainer.compactLidSpace(lidLimit, serialNum);
}

} // namespace proton

