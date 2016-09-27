// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "disk_index_stats.h"
#include "idiskindex.h"


namespace searchcorespi {
namespace index {

DiskIndexStats::DiskIndexStats()
    : IndexSearchableStats(),
      _indexDir()
{
}

DiskIndexStats::DiskIndexStats(const IDiskIndex &index)
    : IndexSearchableStats(index),
      _indexDir(index.getIndexDir())
{
}

DiskIndexStats::~DiskIndexStats()
{
}

} // namespace searchcorespi::index
} // namespace searchcorespi
