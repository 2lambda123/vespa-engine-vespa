// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/stllike/string.h>

namespace search { class AttributeVector; }

namespace proton {

/*
 * Interface class for access attribute in correct attribute write
 * thread as async callback from asyncForEachAttribute() call on
 * attribute manager.
 */
class IAttributeFunctor
{
public:
    virtual void operator()(const search::AttributeVector &attributeVector) = 0;
    virtual ~IAttributeFunctor() { }
};

class IAttributeExecutor {
public:
    virtual ~IAttributeExecutor() { }
    virtual void asyncForAttribute(const vespalib::string &name, std::shared_ptr<IAttributeFunctor> func) const = 0;
};

} // namespace proton

