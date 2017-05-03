// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "floatfieldsearcher.h"

using search::QueryTerm;
using search::QueryTermList;

namespace vsm {

IMPLEMENT_DUPLICATE(FloatFieldSearcher);
IMPLEMENT_DUPLICATE(DoubleFieldSearcher);

template<typename T>
FloatFieldSearcherT<T>::FloatFieldSearcherT(FieldIdT fId) :
  FieldSearcher(fId),
  _floatTerm()
{}

template<typename T>
FloatFieldSearcherT<T>::~FloatFieldSearcherT() {}

template<typename T>
void FloatFieldSearcherT<T>::prepare(QueryTermList & qtl, const SharedSearcherBuf & buf)
{
  FieldSearcher::prepare(qtl, buf);
  for (QueryTermList::const_iterator it=qtl.begin(); it < qtl.end(); it++) {
    const QueryTerm * qt = *it;
    size_t sz(qt->termLen());
    if (sz) {
      double low;
      double high;
      bool valid = qt->getAsDoubleTerm(low, high);
      _floatTerm.push_back(FloatInfo(low, high, valid));
    }
  }
}


template<typename T>
void FloatFieldSearcherT<T>::onValue(const document::FieldValue & fv)
{
    for(size_t j=0, jm(_floatTerm.size()); j < jm; j++) {
        const FloatInfo & ii = _floatTerm[j];
        if (ii.valid() && (ii.cmp(fv.getAsDouble()))) {
            addHit(*_qtl[j], 0);
        }
    }
    ++_words;
}

template<typename T>
bool FloatFieldSearcherT<T>::FloatInfo::cmp(T key) const
{
    return (_lower <= key) && (key <= _upper);
}

template class FloatFieldSearcherT<float>;
template class FloatFieldSearcherT<double>;

}
