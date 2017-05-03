// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "utf8strchrfieldsearcher.h"

namespace vsm {

class FUTF8StrChrFieldSearcher : public UTF8StrChrFieldSearcher
{
public:
    DUPLICATE(FUTF8StrChrFieldSearcher);
    FUTF8StrChrFieldSearcher();
    FUTF8StrChrFieldSearcher(FieldIdT fId);
    ~FUTF8StrChrFieldSearcher();
    static bool ansiFold(const char * toFold, size_t sz, char * folded);
    static bool lfoldaa(const char * toFold, size_t sz, char * folded, size_t & unalignedStart);
    static bool lfoldua(const char * toFold, size_t sz, char * folded, size_t & alignedStart);
 private:
    size_t matchTerm(const FieldRef & f, search::QueryTerm & qt) override;
    size_t matchTerms(const FieldRef&, const size_t shortestTerm) override;
    virtual size_t match(const char *folded, size_t sz, search::QueryTerm & qt);
    size_t match(const char *folded, size_t sz, size_t mintsz, search::QueryTerm ** qtl, size_t qtlSize);
    std::vector<char> _folded;
};

}
