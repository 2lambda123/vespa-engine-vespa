// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "attribute_combiner_dfw.h"
#include "array_attribute_combiner_dfw.h"
#include "struct_map_attribute_combiner_dfw.h"
#include "docsum_field_writer_state.h"
#include "docsumstate.h"
#include <vespa/searchlib/attribute/attributeguard.h>
#include <vespa/searchlib/attribute/attributevector.h>
#include <vespa/searchlib/attribute/iattributemanager.h>
#include <vespa/searchlib/common/struct_field_mapper.h>
#include <algorithm>

#include <vespa/log/log.h>
LOG_SETUP(".searchsummary.docsummary.attribute_combiner_dfw");

using search::AttributeGuard;
using search::AttributeVector;
using search::attribute::CollectionType;

namespace search::docsummary {

namespace {

class StructFields
{
    std::vector<vespalib::string> _mapFields;
    std::vector<vespalib::string> _arrayFields;
    bool _hasMapKey;
    bool _error;

public:
    StructFields(const vespalib::string &fieldName, const IAttributeManager &attrMgr);
    ~StructFields();
    const std::vector<vespalib::string> &getMapFields() const { return _mapFields; }
    const std::vector<vespalib::string> &getArrayFields() const { return _arrayFields; }
    bool hasMapKey() const { return _hasMapKey; }
    bool getError() const { return _error; }
};


StructFields::StructFields(const vespalib::string &fieldName, const IAttributeManager &attrMgr)
    : _mapFields(),
      _arrayFields(),
      _hasMapKey(false),
      _error(false)
{
    std::vector<const search::attribute::IAttributeVector *> attrs;
    auto attrCtx = attrMgr.createContext();
    attrCtx->getAttributeList(attrs);
    vespalib::string prefix = fieldName + ".";
    vespalib::string keyName = prefix + "key";
    vespalib::string valuePrefix = prefix + "value.";
    for (const auto attr : attrs) {
        vespalib::string name = attr->getName();
        if (name.substr(0, prefix.size()) != prefix) {
            continue;
        }
        auto collType = attr->getCollectionType();
        if (collType != CollectionType::Type::ARRAY) {
            LOG(warning, "Attribute %s is not an array attribute", name.c_str());
            _error = true;
            break;
        }
        if (name.substr(0, valuePrefix.size()) == valuePrefix) {
            _mapFields.emplace_back(name.substr(valuePrefix.size()));
        } else {
            _arrayFields.emplace_back(name.substr(prefix.size()));
            if (name == keyName) {
                _hasMapKey = true;
            }
        }
    }
    if (!_error) {
        std::sort(_arrayFields.begin(), _arrayFields.end());
        std::sort(_mapFields.begin(), _mapFields.end());
        if (!_mapFields.empty()) {
            if (!_hasMapKey) {
                LOG(warning, "Missing key attribute '%s', have value attributes for map", keyName.c_str());
                _error = true;
            } else if (_arrayFields.size() != 1u) {
                LOG(warning, "Could not determine if field '%s' is array or map of struct", fieldName.c_str());
                _error = true;
            }
        }
    }
}

StructFields::~StructFields() = default;

}

AttributeCombinerDFW::AttributeCombinerDFW(const vespalib::string &fieldName, bool filter_elements, std::shared_ptr<StructFieldMapper> struct_field_mapper)
    : ISimpleDFW(),
      _stateIndex(0),
      _filter_elements(filter_elements),
      _fieldName(fieldName),
      _struct_field_mapper(std::move(struct_field_mapper))
{
}

AttributeCombinerDFW::~AttributeCombinerDFW() = default;

bool
AttributeCombinerDFW::IsGenerated() const
{
    return true;
}

bool
AttributeCombinerDFW::setFieldWriterStateIndex(uint32_t fieldWriterStateIndex)
{
    _stateIndex = fieldWriterStateIndex;
    return true;
}

std::unique_ptr<IDocsumFieldWriter>
AttributeCombinerDFW::create(const vespalib::string &fieldName, IAttributeManager &attrMgr, bool filter_elements, std::shared_ptr<StructFieldMapper> struct_field_mapper)
{
    StructFields structFields(fieldName, attrMgr);
    if (structFields.getError()) {
        return std::unique_ptr<IDocsumFieldWriter>();
    } else if (!structFields.getMapFields().empty()) {
        return std::make_unique<StructMapAttributeCombinerDFW>(fieldName, structFields.getMapFields(), filter_elements, std::move(struct_field_mapper));
    }
    return std::make_unique<ArrayAttributeCombinerDFW>(fieldName, structFields.getArrayFields(), filter_elements, std::move(struct_field_mapper));
}

void
AttributeCombinerDFW::insertField(uint32_t docid, GetDocsumsState *state, ResType, vespalib::slime::Inserter &target)
{
    auto &fieldWriterState = state->_fieldWriterStates[_stateIndex];
    if (!fieldWriterState) {
        const MatchingElements *matching_elements = nullptr;
        if (_filter_elements) {
            matching_elements = &state->get_matching_elements(*_struct_field_mapper);
        }
        fieldWriterState = allocFieldWriterState(*state->_attrCtx, matching_elements);
    }
    fieldWriterState->insertField(docid, target);
}

}

