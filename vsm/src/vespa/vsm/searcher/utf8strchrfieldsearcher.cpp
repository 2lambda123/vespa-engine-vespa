// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "utf8strchrfieldsearcher.h"

using search::QueryTerm;
using search::QueryTermList;
using search::byte;

namespace vsm {

IMPLEMENT_DUPLICATE(UTF8StrChrFieldSearcher);

size_t
UTF8StrChrFieldSearcher::matchTerms(const FieldRef & f, const size_t mintsz)
{
    (void) mintsz;
    termcount_t words(0);
    const byte * n = reinterpret_cast<const byte *> (f.data());
    const byte * e = n + f.size();
    if (f.size() >= _buf->size()) {
        _buf->reserve(f.size() + 1);
    }
    cmptype_t * fn = &(*_buf.get())[0];
    size_t fl(0);

    for( ; n < e; ) {
        if (!*n) { _zeroCount++; n++; }
        n = tokenize(n, _buf->capacity(), fn, fl);
        for(QueryTermList::iterator it=_qtl.begin(), mt=_qtl.end(); it != mt; it++) {
            QueryTerm & qt = **it;
            const cmptype_t * term;
            termsize_t tsz = qt.term(term);
            if ((tsz <= fl) && (prefix() || qt.isPrefix() || (tsz == fl))) {
                const cmptype_t *tt=term, *et=term+tsz;
                for (const cmptype_t *fnt=fn; (tt < et) && (*tt == *fnt); tt++, fnt++);
                if (tt == et) {
                    addHit(qt, words);
                }
            }
        }
        words++;
    }
    NEED_CHAR_STAT(addAnyUtf8Field(f.size()));
    return words;
}

size_t
UTF8StrChrFieldSearcher::matchTerm(const FieldRef & f, QueryTerm & qt)
{
    return matchTermRegular(f, qt);
}

}
