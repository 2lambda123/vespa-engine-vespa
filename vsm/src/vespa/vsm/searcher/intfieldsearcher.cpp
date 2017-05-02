// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "intfieldsearcher.h"

using search::QueryTerm;
using search::QueryTermList;

namespace vsm {

IMPLEMENT_DUPLICATE(IntFieldSearcher);

IntFieldSearcher::IntFieldSearcher(FieldIdT fId) :
  FieldSearcher(fId),
  _intTerm()
{ }

IntFieldSearcher::~IntFieldSearcher() {}

void IntFieldSearcher::prepare(QueryTermList & qtl, const SharedSearcherBuf & buf)
{
  FieldSearcher::prepare(qtl, buf);
  for (QueryTermList::const_iterator it=qtl.begin(); it < qtl.end(); it++) {
    const QueryTerm * qt = *it;
    size_t sz(qt->termLen());
    if (sz) {
      int64_t low;
      int64_t high;
      bool valid = qt->getAsIntegerTerm(low, high);
      _intTerm.push_back(IntInfo(low, high, valid));
    }
  }
}

void IntFieldSearcher::onValue(const document::FieldValue & fv)
{
    for(size_t j=0, jm(_intTerm.size()); j < jm; j++) {
        const IntInfo & ii = _intTerm[j];
        if (ii.valid() && (ii.cmp(fv.getAsLong()))) {
            addHit(*_qtl[j], 0);
        }
    }
    ++_words;
}

}
