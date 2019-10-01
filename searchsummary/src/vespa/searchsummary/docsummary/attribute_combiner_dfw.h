// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "docsumfieldwriter.h"

namespace search { class MatchingElements; }
namespace search::attribute { class IAttributeContext; }

namespace search::docsummary {

class DocsumFieldWriterState;
class DynamicDocsumWriter;

/*
 * This class reads values from multiple struct field attributes and
 * inserts them as an array of struct or a map of struct.
 */
class AttributeCombinerDFW : public ISimpleDFW
{
protected:
    uint32_t _stateIndex;
    const bool _filter_elements;
    vespalib::string _fieldName;
    AttributeCombinerDFW(const vespalib::string &fieldName, bool filter_elements);
protected:
    virtual std::unique_ptr<DocsumFieldWriterState> allocFieldWriterState(search::attribute::IAttributeContext &context, const MatchingElements* matching_elements) = 0;
public:
    ~AttributeCombinerDFW() override;
    bool IsGenerated() const override;
    bool setFieldWriterStateIndex(uint32_t fieldWriterStateIndex) override;
    static std::unique_ptr<IDocsumFieldWriter> create(const vespalib::string &fieldName, IAttributeManager &attrMgr, bool filter_elements);
    void insertField(uint32_t docid, GetDocsumsState *state, ResType type, vespalib::slime::Inserter &target) override;
};

}

