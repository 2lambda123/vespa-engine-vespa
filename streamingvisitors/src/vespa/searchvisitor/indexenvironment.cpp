// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "indexenvironment.h"

using namespace search::fef;

namespace storage {

IndexEnvironment::IndexEnvironment(const ITableManager & tableManager) :
    _tableManager(&tableManager),
    _properties(),
    _fields(),
    _fieldNames(),
    _motivation(RANK),
    _rankAttributes(),
    _dumpAttributes()
{
}

IndexEnvironment::~IndexEnvironment() {}

bool
IndexEnvironment::addField(const vespalib::string & name, bool isAttribute)
{
    if (getFieldByName(name) != NULL) {
        return false;
    }
    FieldInfo info(isAttribute ? FieldType::ATTRIBUTE : FieldType::INDEX, CollectionType::SINGLE, name, _fields.size());
    info.addAttribute(); // we are able to produce needed attributes at query time
    _fields.push_back(info);
    _fieldNames[info.name()] = info.id();
    return true;
}

} // namespace storage

