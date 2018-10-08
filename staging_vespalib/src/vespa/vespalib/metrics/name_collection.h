// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <mutex>
#include <map>
#include <vector>
#include <vespa/vespalib/stllike/string.h>

namespace vespalib {
namespace metrics {

// internal
class NameCollection {
private:
    using Map = std::map<vespalib::string, size_t>;
    mutable std::mutex _lock;
    Map _names;
    std::vector<Map::const_iterator> _names_by_id;
public:
    const vespalib::string &lookup(size_t id) const;
    size_t resolve(const vespalib::string& name);
    size_t size() const;

    NameCollection();
    ~NameCollection() {}
};

} // namespace vespalib::metrics
} // namespace vespalib
