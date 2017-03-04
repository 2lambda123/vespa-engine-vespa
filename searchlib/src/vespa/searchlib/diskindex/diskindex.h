// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/stllike/cache.h>
#include <vespa/searchlib/diskindex/bitvectordictionary.h>
#include <vespa/searchlib/index/dictionaryfile.h>
#include <vespa/searchlib/diskindex/zcposoccrandread.h>
#include <vespa/searchlib/queryeval/searchable.h>
#include <vespa/vespalib/stllike/string.h>

namespace search {

namespace diskindex {

/**
 * This class represents a disk index with a common dictionary, and
 * posting list files and bit vector files for each field.
 * Parts of the disk dictionary and all bit vector
 * dictionaries are loaded into memory during setup.  All other files
 * are just opened, ready for later access.
 **/
class DiskIndex : public queryeval::Searchable
{
public:
    /**
     * The result after performing a disk dictionary lookup.
     **/
    struct LookupResult {
        uint32_t                         indexId;
        uint64_t                         wordNum;
        index::PostingListCounts counts;
        uint64_t                         bitOffset;
        typedef std::unique_ptr<LookupResult> UP;
        LookupResult();
        bool valid() const { return counts._numDocs > 0; }
        void swap(LookupResult & rhs) {
            std::swap(indexId , rhs.indexId);
            std::swap(wordNum , rhs.wordNum);
            counts.swap(rhs.counts);
            std::swap(bitOffset , rhs.bitOffset);
        }
    };
    typedef std::vector<LookupResult> LookupResultVector;
    typedef std::vector<uint32_t> IndexList;

    class Key {
    public:
        Key();
        Key(const IndexList & indexes, vespalib::stringref word);
        ~Key();
        uint32_t hash() const {
            return vespalib::hashValue(_word.c_str(), _word.size());
        }
        bool operator == (const Key & rhs) const {
            return _word == rhs._word;
        }
        void push_back(uint32_t indexId) { _indexes.push_back(indexId); }
        const IndexList & getIndexes() const { return _indexes; }
        const vespalib::string & getWord() const { return _word; }
    private:
        vespalib::string _word;
        IndexList        _indexes;
    };
private:
    typedef index::PostingListFileRandRead DiskPostingFile;
    typedef Zc4PosOccRandRead DiskPostingFileReal;
    typedef ZcPosOccRandRead DiskPostingFileDynamicKReal;
    typedef vespalib::cache<vespalib::CacheParam<vespalib::LruParam<Key, LookupResultVector>, DiskIndex>> Cache;

    vespalib::string                       _indexDir;
    size_t                                 _cacheSize;
    index::Schema                          _schema;
    std::vector<DiskPostingFile::SP>       _postingFiles;
    std::vector<BitVectorDictionary::SP>   _bitVectorDicts;
    std::vector<std::unique_ptr<index::DictionaryFileRandRead>> _dicts;
    TuneFileSearch                         _tuneFileSearch;
    Cache                                  _cache;
    uint64_t                               _size;

    void calculateSize();

    bool
    loadSchema(void);

    bool
    openDictionaries(const TuneFileSearch &tuneFileSearch);

    bool
    openField(const vespalib::string &fieldDir,
              const TuneFileSearch &tuneFileSearch);

public:
    /**
     * Create a view of the disk index located in the given directory
     * described by the given schema.
     *
     * @param indexDir the directory where the disk index is located.
     **/
    DiskIndex(const vespalib::string &indexDir, size_t cacheSize=0);

    /**
     * Setup this instance by opening and loading relevant index files.
     *
     * @return true if this instance was successfully setup.
     **/
    bool
    setup(const TuneFileSearch &tuneFileSearch);

    bool
    setup(const TuneFileSearch &tuneFileSearch, const DiskIndex &old);

    /**
     * Perform a dictionary lookup for the given word in the given
     * field.
     *
     * @param indexId the id of the field to
     *                perform lookup for.
     * @param word the word to lookup.
     * @return the lookup result or NULL if the word is not found.
     **/
    LookupResult::UP
    lookup(uint32_t indexId, const vespalib::stringref & word);

    LookupResultVector
    lookup(const std::vector<uint32_t> & indexes, const vespalib::stringref & word);


    /**
     * Read the posting list corresponding to the given lookup result.
     *
     * @param lookupRes the result of the previous dictionary lookup.
     * @return a handle for the posting list in memory.
     **/
    index::PostingListHandle::UP
    readPostingList(const LookupResult &lookupRes) const;

    /**
     * Read the bit vector corresponding to the given lookup result.
     *
     * @param lookupRes the result of the previous dictionary lookup.
     * @return the bit vector or NULL if no bit vector exists for the
     *         word in the lookup result.
     **/
    BitVector::UP
    readBitVector(const LookupResult &lookupRes) const;

    // Inherit doc from Searchable
    virtual queryeval::Blueprint::UP
    createBlueprint(const queryeval::IRequestContext & requestContext,
                    const queryeval::FieldSpec &field,
                    const query::Node &term);

    virtual queryeval::Blueprint::UP
    createBlueprint(const queryeval::IRequestContext & requestContext,
                    const queryeval::FieldSpecList &fields,
                    const query::Node &term);

    /**
     * Get the size on disk of this index.
     * @return the size of the index.
     */
    uint64_t getSize() const { return _size; }

    const index::Schema &
    getSchema(void) const
    {
        return _schema;
    }

    const vespalib::string &
    getIndexDir(void) const
    {
        return _indexDir;
    }

    const TuneFileSearch &
    getTuneFileSearch(void) const
    {
        return _tuneFileSearch;
    }

    /**
     * Needed for the Cache::BackingStore interface.
     */ 
    bool read(const Key & key, LookupResultVector & result);
};

void swap(DiskIndex::LookupResult & a, DiskIndex::LookupResult & b);

} // namespace diskindex

} // namespace search
