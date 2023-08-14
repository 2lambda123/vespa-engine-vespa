// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vespalib/stllike/hash_set.h>

namespace storage::distributor {

/*
 * Bundle of ideal service layer nodes for a bucket.
 */
class IdealServiceLayerNodesBundle {
    std::vector<uint16_t> _available_nodes;
    std::vector<uint16_t> _available_nonretired_nodes;
    std::vector<uint16_t> _available_nonretired_or_maintenance_nodes;
    vespalib::hash_set<uint16_t> _unordered_nonretired_or_maintenance_nodes;
public:
    IdealServiceLayerNodesBundle() noexcept;
    IdealServiceLayerNodesBundle(IdealServiceLayerNodesBundle &&) noexcept;
    ~IdealServiceLayerNodesBundle();

    void set_available_nodes(std::vector<uint16_t> available_nodes) {
        _available_nodes = std::move(available_nodes);
    }
    void set_available_nonretired_nodes(std::vector<uint16_t> available_nonretired_nodes) {
        _available_nonretired_nodes = std::move(available_nonretired_nodes);
    }
    void set_available_nonretired_or_maintenance_nodes(std::vector<uint16_t> available_nonretired_or_maintenance_nodes);
    const std::vector<uint16_t> & available_nodes() const noexcept { return _available_nodes; }
    const std::vector<uint16_t> & available_nonretired_nodes() const noexcept { return _available_nonretired_nodes; }
    const std::vector<uint16_t> & available_nonretired_or_maintenance_nodes() const noexcept {
        return _available_nonretired_or_maintenance_nodes;
    }
    bool is_nonretired_or_maintenance(uint16_t node) const noexcept {
        return _unordered_nonretired_or_maintenance_nodes.contains(node);
    }
};

}
