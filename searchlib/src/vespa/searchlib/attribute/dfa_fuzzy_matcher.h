// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "dfa_string_comparator.h"
#include <vespa/vespalib/datastore/atomic_entry_ref.h>
#include <vespa/vespalib/fuzzy/levenshtein_dfa.h>
#include <iostream>

namespace search::attribute {

/**
 * Class that uses a LevenshteinDfa to fuzzy match a target word against words in a dictionary.
 *
 * The dictionary iterator is advanced based on the successor string from the DFA
 * each time the candidate word is _not_ a match.
 */
class DfaFuzzyMatcher {
private:
    vespalib::fuzzy::LevenshteinDfa _dfa;
    std::vector<uint32_t>           _successor;
    std::vector<uint32_t>           _prefix;
    uint32_t                        _prefix_size;
    bool                            _cased;

    const char* skip_prefix(const char* word) const;
public:
    DfaFuzzyMatcher(std::string_view target, uint8_t max_edits, uint32_t prefix_size, bool cased, vespalib::fuzzy::LevenshteinDfa::DfaType dfa_type);
    ~DfaFuzzyMatcher();

    bool is_match(const char *word) const;

    /*
     * If prefix size is nonzero then this variant of is_match()
     * should only be called with words that starts with the extracted
     * prefix of the target word.
     *
     * Caller must position iterator at right location using lower bound
     * functionality in the dictionary.
     */
    template <typename DictionaryConstIteratorType>
    bool is_match(const char* word, DictionaryConstIteratorType& itr, const DfaStringComparator::DataStoreType& data_store) {
        if (_prefix_size > 0) {
            word = skip_prefix(word);
            if (_prefix.size() < _prefix_size) {
                if (*word == '\0') {
                    return true;
                }
                _successor.resize(_prefix.size());
                _successor.emplace_back(1);
            } else {
                _successor.resize(_prefix.size());
                auto match = _dfa.match(word, _successor);
                if (match.matches()) {
                    return true;
                }
            }
        } else {
            _successor.clear();
            auto match = _dfa.match(word, _successor);
            if (match.matches()) {
                return true;
            }
        }
        DfaStringComparator cmp(data_store, _successor);
        itr.seek(vespalib::datastore::AtomicEntryRef(), cmp);
        return false;
    }
};

}
