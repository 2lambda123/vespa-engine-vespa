// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright (C) 2002-2003 Fast Search & Transfer ASA
// Copyright (C) 2003 Overture Services Norway AS

#pragma once
#include <vespa/searchlib/index/docidandfeatures.h>
#include <vespa/searchcommon/common/schema.h>


#define K_VALUE_POSOCC_FIRST_DOCID 22
#define MAXRICE2_POSOCC_FIRST_DOCID MAX_RICE2VAL_L32_K22

#define K_VALUE_POSOCC_DELTA_DOCID 7
#define MAXRICE2_POSOCC_DELTA_DOCID MAX_RICE2VAL_L30_K7

#define K_VALUE_POSOCC_FIRST_WORDPOS 8
#define MAXRICE2_POSOCC_FIRST_WORDPOS MAX_RICE2VAL_L32_K8

#define K_VALUE_POSOCC_DELTA_WORDPOS 4
#define MAXRICE2_POSOCC_DELTA_WORDPOS MAX_RICE2VAL_L31_K4

// Compression parameters for EGPosOcc encode/decode context
#define K_VALUE_POSOCC_ELEMENTLEN 9
#define K_VALUE_POSOCC_NUMPOSITIONS 0
#define K_VALUE_POSOCC_NUMFIELDS 0
#define K_VALUE_POSOCC_FIELDID 0

#define K_VALUE_POSOCC_NUMELEMENTS 0
#define K_VALUE_POSOCC_ELEMENTID 0
#define K_VALUE_POSOCC_ELEMENTWEIGHT 9

namespace search
{

namespace index
{

class DocIdAndPosOccFeatures : public DocIdAndFeatures
{
public:

    void
    addNextOcc(uint32_t elementId,
               uint32_t wordPos,
               int32_t elementWeight,
               uint32_t elementLen)
    {
        assert(wordPos < elementLen);
        if (_elements.empty() ||
            elementId > _elements.back().getElementId()) {
            _elements.emplace_back(elementId, elementWeight, elementLen);
        } else {
            assert(elementId == _elements.back().getElementId());
            assert(elementWeight == _elements.back().getWeight());
            assert(elementLen == _elements.back().getElementLen());
        }
        assert(_elements.back().getNumOccs() == 0 ||
               wordPos > _wordPositions.back().getWordPos());
        _elements.back().incNumOccs();
        _wordPositions.emplace_back(wordPos);
    }
};

} // namespace search::index

} // namespace search


namespace search
{

namespace bitcompression
{

class PosOccFieldParams
{
public:
    typedef index::PostingListParams PostingListParams;
    typedef index::Schema Schema;

    enum CollectionType
    {
        SINGLE,
        ARRAY,
        WEIGHTEDSET
    };

    uint8_t _elemLenK;
    bool    _hasElements;
    bool    _hasElementWeights;
    uint32_t _avgElemLen;
    CollectionType _collectionType;
    vespalib::string _name;

    PosOccFieldParams(void);

    bool
    operator==(const PosOccFieldParams &rhs) const;

    static vespalib::string
    getParamsPrefix(uint32_t idx);

    void
    getParams(PostingListParams &params, uint32_t idx) const;

    void
    setParams(const PostingListParams &params, uint32_t idx);

    void
    setSchemaParams(const Schema &schema, uint32_t fieldId);

    void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    void
    writeHeader(vespalib::GenericHeader &header,
                const vespalib::string &prefix) const;
};


class PosOccFieldsParams
{
    // Cache pointers.
    uint32_t _numFields;
    const PosOccFieldParams *_fieldParams;

    // Storage
    std::vector<PosOccFieldParams> _params;

public:
    typedef index::PostingListParams PostingListParams;
    typedef index::Schema Schema;

    PosOccFieldsParams(void);

    PosOccFieldsParams(const PosOccFieldsParams &rhs);

    PosOccFieldsParams &
    operator=(const PosOccFieldsParams &rhs);

    bool
    operator==(const PosOccFieldsParams &rhs) const;

    void
    cacheParamsRef(void)
    {
        _numFields = _params.size();
        _fieldParams = _params.empty() ? NULL : &_params[0];
    }

    void
    assertCachedParamsRef(void) const
    {
        assert(_numFields == _params.size());
        assert(_fieldParams == (_params.empty() ? NULL : &_params[0]));
    }

    uint32_t
    getNumFields(void) const
    {
        return _numFields;
    }

    const PosOccFieldParams *
    getFieldParams(void) const
    {
        return _fieldParams;
    }

    void
    getParams(PostingListParams &params) const;

    void
    setParams(const PostingListParams &params);

    void
    setSchemaParams(const Schema &schema, const uint32_t indexId);

    void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    void
    writeHeader(vespalib::GenericHeader &header,
                const vespalib::string &prefix) const;
};

template <bool bigEndian>
class EG2PosOccDecodeContext : public FeatureDecodeContext<bigEndian>
{
public:
    typedef FeatureDecodeContext<bigEndian> ParentClass;
    using ParentClass::smallAlign;
    using ParentClass::readBits;
    using ParentClass::_valI;
    using ParentClass::_val;
    using ParentClass::_cacheInt;
    using ParentClass::_preRead;
    using ParentClass::_valE;
    using ParentClass::_fileReadBias;
    using ParentClass::_readContext;
    using ParentClass::readHeader;
    typedef EncodeContext64<bigEndian> EC;
    typedef index::PostingListParams PostingListParams;

    const PosOccFieldsParams *_fieldsParams;

    EG2PosOccDecodeContext(const PosOccFieldsParams *fieldsParams)
        : FeatureDecodeContext<bigEndian>(),
          _fieldsParams(fieldsParams)
    {
    }

    EG2PosOccDecodeContext(const uint64_t *compr, int bitOffset,
                           const PosOccFieldsParams *fieldsParams)
        : FeatureDecodeContext<bigEndian>(compr, bitOffset),
          _fieldsParams(fieldsParams)
    {
    }


    EG2PosOccDecodeContext(const uint64_t *compr,
                           int bitOffset,
                           uint64_t bitLength,
                           const PosOccFieldsParams *fieldsParams)
        : FeatureDecodeContext<bigEndian>(compr, bitOffset, bitLength),
          _fieldsParams(fieldsParams)
    {
    }


    EG2PosOccDecodeContext &
    operator=(const EG2PosOccDecodeContext &rhs)
    {
        FeatureDecodeContext<bigEndian>::operator=(rhs);
        _fieldsParams = rhs._fieldsParams;
        return *this;
    }

    virtual void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    virtual const vespalib::string &
    getIdentifier(void) const;

    virtual void
    readFeatures(search::index::DocIdAndFeatures &features);

    virtual void
    skipFeatures(unsigned int count);

    virtual void
    unpackFeatures(const search::fef::TermFieldMatchDataArray &matchData,
                   uint32_t docId);

    /*
     * Set parameters.
     */
    virtual void
    setParams(const PostingListParams &params);

    /*
     * Get current parameters.
     */
    virtual void
    getParams(PostingListParams &params) const;
};


template <bool bigEndian>
class EG2PosOccDecodeContextCooked : public EG2PosOccDecodeContext<bigEndian>
{
public:
    typedef EG2PosOccDecodeContext<bigEndian> ParentClass;
    using ParentClass::smallAlign;
    using ParentClass::readBits;
    using ParentClass::_valI;
    using ParentClass::_val;
    using ParentClass::_cacheInt;
    using ParentClass::_preRead;
    using ParentClass::_valE;
    using ParentClass::_fileReadBias;
    using ParentClass::_readContext;
    using ParentClass::_fieldsParams;
    typedef EncodeContext64<bigEndian> EC;
    typedef index::PostingListParams PostingListParams;

    EG2PosOccDecodeContextCooked(const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(fieldsParams)
    {
    }

    EG2PosOccDecodeContextCooked(const uint64_t *compr, int bitOffset,
                                 const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(compr, bitOffset, fieldsParams)
    {
    }


    EG2PosOccDecodeContextCooked(const uint64_t *compr,
                                 int bitOffset,
                                 uint64_t bitLength,
                                 const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(compr, bitOffset, bitLength,
                fieldsParams)
    {
    }


    EG2PosOccDecodeContextCooked &
    operator=(const EG2PosOccDecodeContext<bigEndian> &rhs)
    {
        EG2PosOccDecodeContext<bigEndian>::operator=(rhs);
        return *this;
    }

    virtual void
    readFeatures(search::index::DocIdAndFeatures &features);

    virtual void
    getParams(PostingListParams &params) const;
};


template <bool bigEndian>
class EG2PosOccEncodeContext : public FeatureEncodeContext<bigEndian>
{
public:
    typedef FeatureEncodeContext<bigEndian> ParentClass;
    typedef index::DocIdAndFeatures DocIdAndFeatures;
    typedef index::PostingListParams PostingListParams;
    using ParentClass::smallAlign;
    using ParentClass::writeBits;
    using ParentClass::_valI;
    using ParentClass::_valE;
    using ParentClass::_writeContext;
    using ParentClass::encodeExpGolomb;
    using ParentClass::readHeader;
    using ParentClass::writeHeader;

    const PosOccFieldsParams *_fieldsParams;

    EG2PosOccEncodeContext(const PosOccFieldsParams *fieldsParams)
        : FeatureEncodeContext<bigEndian>(),
          _fieldsParams(fieldsParams)
    {
    }

    EG2PosOccEncodeContext &
    operator=(const EG2PosOccEncodeContext &rhs)
    {
        FeatureEncodeContext<bigEndian>::operator=(rhs);
        _fieldsParams = rhs._fieldsParams;
        return *this;
    }

    virtual void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    virtual void
    writeHeader(vespalib::GenericHeader &header,
                const vespalib::string &prefix) const;

    virtual const vespalib::string &
    getIdentifier(void) const;

    virtual void
    writeFeatures(const DocIdAndFeatures &features);

    /*
     * Set parameters.
     */
    virtual void
    setParams(const PostingListParams &params);

    /*
     * Get current parameters.
     */
    virtual void
    getParams(PostingListParams &params) const;
};


template <bool bigEndian>
class EGPosOccDecodeContext : public EG2PosOccDecodeContext<bigEndian>
{
public:
    typedef EG2PosOccDecodeContext<bigEndian> ParentClass;
    typedef index::DocIdAndFeatures DocIdAndFeatures;
    typedef index::PostingListParams PostingListParams;
    using ParentClass::smallAlign;
    using ParentClass::readBits;
    using ParentClass::_valI;
    using ParentClass::_val;
    using ParentClass::_cacheInt;
    using ParentClass::_preRead;
    using ParentClass::_valE;
    using ParentClass::_fileReadBias;
    using ParentClass::_readContext;
    using ParentClass::_fieldsParams;
    using ParentClass::readHeader;
    typedef EncodeContext64<bigEndian> EC;

    EGPosOccDecodeContext(const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(fieldsParams)
    {
    }

    EGPosOccDecodeContext(const uint64_t *compr, int bitOffset,
                          const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(compr, bitOffset, fieldsParams)
    {
    }


    EGPosOccDecodeContext(const uint64_t *compr,
                          int bitOffset,
                          uint64_t bitLength,
                          const PosOccFieldsParams *fieldsParams)
        : EG2PosOccDecodeContext<bigEndian>(compr, bitOffset, bitLength,
                fieldsParams)
    {
    }


    EGPosOccDecodeContext &
    operator=(const EGPosOccDecodeContext &rhs)
    {
        EG2PosOccDecodeContext<bigEndian>::operator=(rhs);
        return *this;
    }

    virtual void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    virtual const vespalib::string &
    getIdentifier(void) const;

    virtual void
    readFeatures(search::index::DocIdAndFeatures &features);

    virtual void
    skipFeatures(unsigned int count);

    virtual void
    unpackFeatures(const search::fef::TermFieldMatchDataArray &matchData,
                   uint32_t docId);

    /*
     * Set parameters.
     */
    virtual void
    setParams(const PostingListParams &params);

    /*
     * Get current parameters.
     */
    virtual void
    getParams(PostingListParams &params) const;
};


template <bool bigEndian>
class EGPosOccDecodeContextCooked : public EGPosOccDecodeContext<bigEndian>
{
public:
    typedef EGPosOccDecodeContext<bigEndian> ParentClass;
    typedef index::DocIdAndFeatures DocIdAndFeatures;
    typedef index::PostingListParams PostingListParams;
    using ParentClass::smallAlign;
    using ParentClass::readBits;
    using ParentClass::_valI;
    using ParentClass::_val;
    using ParentClass::_cacheInt;
    using ParentClass::_preRead;
    using ParentClass::_valE;
    using ParentClass::_fileReadBias;
    using ParentClass::_readContext;
    using ParentClass::_fieldsParams;
    typedef EncodeContext64<bigEndian> EC;

    EGPosOccDecodeContextCooked(const PosOccFieldsParams *fieldsParams)
        : EGPosOccDecodeContext<bigEndian>(fieldsParams)
    {
    }

    EGPosOccDecodeContextCooked(const uint64_t *compr, int bitOffset,
                                const PosOccFieldsParams *fieldsParams)
        : EGPosOccDecodeContext<bigEndian>(compr, bitOffset, fieldsParams)
    {
    }


    EGPosOccDecodeContextCooked(const uint64_t *compr,
                                int bitOffset,
                                uint64_t bitLength,
                                const PosOccFieldsParams *fieldsParams)
        : EGPosOccDecodeContext<bigEndian>(compr, bitOffset, bitLength,
                fieldsParams)
    {
    }


    EGPosOccDecodeContextCooked &
    operator=(const EGPosOccDecodeContext<bigEndian> &rhs)
    {
        EGPosOccDecodeContext<bigEndian>::operator=(rhs);
        return *this;
    }

    virtual void
    readFeatures(search::index::DocIdAndFeatures &features);

    virtual void
    getParams(PostingListParams &params) const;
};


template <bool bigEndian>
class EGPosOccEncodeContext : public EG2PosOccEncodeContext<bigEndian>
{
public:
    typedef EG2PosOccEncodeContext<bigEndian> ParentClass;
    typedef index::DocIdAndFeatures DocIdAndFeatures;
    typedef index::PostingListParams PostingListParams;
    using ParentClass::smallAlign;
    using ParentClass::writeBits;
    using ParentClass::_valI;
    using ParentClass::_valE;
    using ParentClass::_writeContext;
    using ParentClass::asmlog2;
    using ParentClass::encodeExpGolomb;
    using ParentClass::_fieldsParams;
    using ParentClass::readHeader;
    using ParentClass::writeHeader;

    EGPosOccEncodeContext(const PosOccFieldsParams *fieldsParams)
        : EG2PosOccEncodeContext<bigEndian>(fieldsParams)
    {
    }

    EGPosOccEncodeContext &
    operator=(const EGPosOccEncodeContext &rhs)
    {
        EG2PosOccEncodeContext<bigEndian>::operator=(rhs);
        return *this;
    }

    virtual void
    readHeader(const vespalib::GenericHeader &header,
               const vespalib::string &prefix);

    virtual void
    writeHeader(vespalib::GenericHeader &header,
                const vespalib::string &prefix) const;

    virtual const vespalib::string &
    getIdentifier(void) const;

    virtual void
    writeFeatures(const DocIdAndFeatures &features);

    /*
     * Set parameters.
     */
    virtual void
    setParams(const PostingListParams &params);

    /*
     * Get current parameters.
     */
    virtual void
    getParams(PostingListParams &params) const;

    static uint32_t
    calcElementLenK(uint32_t avgElementLen)
    {
        return (avgElementLen < 4) ? 1u : (asmlog2(avgElementLen));
    }

    static uint32_t
    calcWordPosK(uint32_t numPositions, uint32_t elementLen)
    {
        uint32_t avgDelta = elementLen / (numPositions + 1);
        uint32_t wordPosK = (avgDelta < 4) ? 1 : (asmlog2(avgDelta));
        return wordPosK;
    }
};


extern template class EG2PosOccDecodeContext<true>;
extern template class EG2PosOccDecodeContext<false>;

extern template class EG2PosOccDecodeContextCooked<true>;
extern template class EG2PosOccDecodeContextCooked<false>;

extern template class EG2PosOccEncodeContext<true>;
extern template class EG2PosOccEncodeContext<false>;

extern template class EGPosOccDecodeContext<true>;
extern template class EGPosOccDecodeContext<false>;

extern template class EGPosOccDecodeContextCooked<true>;
extern template class EGPosOccDecodeContextCooked<false>;

extern template class EGPosOccEncodeContext<true>;
extern template class EGPosOccEncodeContext<false>;

} // namespace bitcompression

} // namespace search

