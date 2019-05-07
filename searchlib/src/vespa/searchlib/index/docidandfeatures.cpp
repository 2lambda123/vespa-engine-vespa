// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "docidandfeatures.h"
#include <vespa/log/log.h>
LOG_SETUP(".index.docidandfeatures");

namespace search::index {

DocIdAndFeatures::DocIdAndFeatures()
    : _doc_id(0),
      _elements(),
      _word_positions(),
      _blob(),
      _bit_offset(0u),
      _bit_length(0u),
      _has_raw_data(false)
{
}

DocIdAndFeatures::DocIdAndFeatures(const DocIdAndFeatures &) = default;
DocIdAndFeatures & DocIdAndFeatures::operator = (const DocIdAndFeatures &) = default;
DocIdAndFeatures::~DocIdAndFeatures() { }

}
