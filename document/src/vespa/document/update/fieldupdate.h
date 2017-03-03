// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class document::FieldUpdate
 * \ingroup update
 *
 * \brief Represent a collection of updates to be performed on a single
 * field in a document.
 *
 * It inherits from Printable to produce human readable output when required.
 * Serialization is done through Serializable and XmlSerializable.
 * Deserialization is specially handled as document type is not serialized with
 * the object.
 */
#pragma once

#include "valueupdate.h"
#include <vespa/document/base/field.h>
#include <vespa/document/util/serializable.h>

namespace document {

class Document;
class DocumentType;
class DocumentTypeRepo;

class FieldUpdate : public vespalib::Identifiable,
                    public Printable,
                    public XmlSerializable
{
    Field _field;
    std::vector<ValueUpdate::CP> _updates;

public:
    typedef vespalib::CloneablePtr<FieldUpdate> CP;

    FieldUpdate(const Field& field);
    ~FieldUpdate();

    /**
     * This is a convenience function to construct a field update directly from
     * a byte buffer by deserializing all its content from the buffer.
     *
     * @param type A document type that describes the buffer content.
     * @param buffer A byte buffer that contains a serialized field update.
     * @param serializationVersion The serialization version the update was serialized with.
     */
    FieldUpdate(const DocumentTypeRepo& repo, const DocumentType& type,
                ByteBuffer& buffer, int16_t serializationVersion);

    bool operator==(const FieldUpdate&) const;
    bool operator!=(const FieldUpdate & rhs) const { return ! (*this == rhs); }

    /**
     * Add a value update to this field update.
     *
     * @param update A pointer to the value update to add to this.
     * @return A pointer to this.
     */
    FieldUpdate& addUpdate(const ValueUpdate& update) {
        update.checkCompatibility(_field); // May throw exception.
        _updates.push_back(ValueUpdate::CP(update.clone()));
        return *this;
    }

    const ValueUpdate& operator[](int index) const { return *_updates[index]; }
    ValueUpdate& operator[](int index) { return *_updates[index]; }
    size_t size() const { return _updates.size(); }

    /** @return The non-modifieable list of value updates to perform. */
    const std::vector<ValueUpdate::CP>& getUpdates() const { return _updates; }

    const Field& getField() const { return _field; }

    /**
     * Applies this update object to the given {@link Document} object.
     *
     * @param doc The document to apply this update to.
     */
    void applyTo(Document& doc) const;

    // Printable implementation
    void print(std::ostream& out, bool verbose,
               const std::string& indent) const;

    // XmlSerializable implementation
    void printXml(XmlOutputStream&) const;

    /**
     * Deserializes the given byte buffer into an instance of an update object.
     * Not a Deserializable, as document type is needed as extra information.
     *
     * @param type A document type that describes the buffer content.
     * @param buffer The byte buffer that contains the serialized update object.
     * @param serializationVersion The serialization version the update was serialized with.
     */
    void deserialize(const DocumentTypeRepo& repo, const DocumentType& type,
                     ByteBuffer& buffer, int16_t serializationVersion);

};

} // document

