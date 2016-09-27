// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vespalib/stllike/string.h>

namespace vespalib {

string demangle(const char * native);

template <typename T>
string
getClassName(const T & obj) {
    return demangle(typeid(obj).name());
}

}
