// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/attribute/multivaluemapping.h>
#include "multi_value_mapping2.h"
#include <vespa/vespalib/stllike/string.h>
#include "attributevector.h"

namespace search {

/*
 * Implementation of multi value attribute using an underlying multi value mapping
 *
 * B: Base class
 * M: MultiValueType (MultiValueMapping template argument)
 */
template <typename B, typename M>
class MultiValueAttribute : public B
{
protected:
    typedef typename B::DocId                             DocId;
    typedef typename B::Change                            Change;
    typedef typename B::ChangeVector                      ChangeVector;
    typedef typename B::ChangeVector::const_iterator            ChangeVectorIterator;

    typedef typename M::Value                             MultiValueType;
    using MultiValueMapping = attribute::MultiValueMapping2<MultiValueType>;
    using Histogram = attribute::MultiValueMapping2Base::Histogram;
    typedef typename MultiValueType::ValueType            ValueType;
    typedef std::vector<MultiValueType>                   ValueVector;
    using MultiValueArrayRef = vespalib::ConstArrayRef<MultiValueType>;
    typedef typename ValueVector::iterator                ValueVectorIterator;
    typedef std::vector<std::pair<DocId, ValueVector> >   DocumentValues;

    MultiValueMapping _mvMapping;

    MultiValueMapping &       getMultiValueMapping()       { return _mvMapping; }
    const MultiValueMapping & getMultiValueMapping() const { return _mvMapping; }

    /*
     * Iterate through the change vector and calculate new values for documents with changes
     */
    void applyAttributeChanges(DocumentValues & docValues);

    virtual bool extractChangeData(const Change & c, ValueType & data) = 0;

    /**
     * Called when a new document has been added.
     * Can be overridden by subclasses that need to resize structures as a result of this.
     * Should return true if underlying structures were resized.
     **/
    virtual bool onAddDoc(DocId doc) { (void) doc; return false; }

    virtual AddressSpace getMultiValueAddressSpaceUsage() const override;

public:
    MultiValueAttribute(const vespalib::string & baseFileName, const AttributeVector::Config & cfg);
    virtual ~MultiValueAttribute();

    virtual bool addDoc(DocId & doc);
    virtual uint32_t getValueCount(DocId doc) const;
    virtual const attribute::MultiValueMapping2Base *getMultiValueBase() const override {
        return &getMultiValueMapping();
    }

private:
    virtual int32_t getWeight(DocId doc, uint32_t idx) const;

    virtual uint64_t
    getTotalValueCount(void) const;

public:
    virtual void
    clearDocs(DocId lidLow, DocId lidLimit);

    virtual void
    onShrinkLidSpace();
};

} // namespace search

