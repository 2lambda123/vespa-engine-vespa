// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "bucketownership.h"
#include <vespa/document/bucket/bucketid.h>
#include <vespa/vespalib/stllike/hash_map.h>
#include <memory>
#include <vector>

namespace storage {
class BucketDatabase;
}

namespace storage::lib {
    class ClusterState;
    class Distribution;
}

namespace storage::distributor {

/**
 * A distributor bucket space holds specific state and information required for
 * keeping track of, and computing operations for, a single bucket space:
 *
 * Bucket database instance
 *   Each bucket space has its own entirely separate bucket database.
 * Distribution config
 *   Each bucket space _may_ operate with its own distribution config, in
 *   particular so that redundancy, ready copies etc can differ across
 *   bucket spaces.
 */
class DistributorBucketSpace {
    std::unique_ptr<BucketDatabase>  _bucketDatabase;
    std::shared_ptr<const lib::ClusterState> _clusterState;
    std::shared_ptr<const lib::Distribution> _distribution;
    uint16_t                                 _node_index;
    uint16_t                                 _distribution_bits;
    std::shared_ptr<const lib::ClusterState> _pending_cluster_state;
    std::vector<bool>                        _available_nodes;
    vespalib::hash_map<document::BucketId, BucketOwnership, document::BucketId::hash>       _ownerships;
    vespalib::hash_map<document::BucketId, std::vector<uint16_t>, document::BucketId::hash> _ideal_nodes;

    void clear();
    void enumerate_available_nodes();
public:
    explicit DistributorBucketSpace();
    explicit DistributorBucketSpace(uint16_t node_index);
    ~DistributorBucketSpace();

    DistributorBucketSpace(const DistributorBucketSpace&) = delete;
    DistributorBucketSpace& operator=(const DistributorBucketSpace&) = delete;
    DistributorBucketSpace(DistributorBucketSpace&&) = delete;
    DistributorBucketSpace& operator=(DistributorBucketSpace&&) = delete;

    BucketDatabase& getBucketDatabase() noexcept {
        return *_bucketDatabase;
    }
    const BucketDatabase& getBucketDatabase() const noexcept {
        return *_bucketDatabase;
    }

    void setClusterState(std::shared_ptr<const lib::ClusterState> clusterState);

    const lib::ClusterState &getClusterState() const noexcept { return *_clusterState; }
    const std::shared_ptr<const lib::ClusterState>& cluster_state_sp() const noexcept {
        return _clusterState;
    }

    void setDistribution(std::shared_ptr<const lib::Distribution> distribution);

    // Precondition: setDistribution has been called at least once prior.
    const lib::Distribution& getDistribution() const noexcept {
        return *_distribution;
    }
    const std::shared_ptr<const lib::Distribution>& distribution_sp() const noexcept {
        return _distribution;
    }

    void set_pending_cluster_state(std::shared_ptr<const lib::ClusterState> pending_cluster_state);

    std::vector<uint16_t> get_ideal_nodes_fallback(document::BucketId bucket) const;

    bool owns_bucket_in_state(const lib::Distribution& distribution, const lib::ClusterState& cluster_state, document::BucketId bucket) const;

    /**
     * Returns true if this distributor owns the given bucket in the
     * given cluster and current distribution config.
     */
    bool owns_bucket_in_state(const lib::ClusterState& clusterState, document::BucketId bucket) const;

    /**
     * Returns true if this distributor owns the given bucket with the current
     * cluster state and distribution config.
     */
    bool owns_bucket_in_current_state(document::BucketId bucket) const;

    /**
     * If there is a pending state, returns ownership state of bucket in it.
     * Otherwise always returns "is owned", i.e. it must also be checked in the current state.
     */
    BucketOwnership check_ownership_in_pending_state(document::BucketId bucket) const;
    BucketOwnership check_ownership_in_pending_and_given_state(const lib::Distribution& distribution,
                                                               const lib::ClusterState& clusterState,
                                                               document::BucketId bucket) const;
    BucketOwnership check_ownership_in_pending_and_current_state_fallback(document::BucketId bucket) const;
    const std::vector<bool>& get_available_nodes() const { return _available_nodes; }
    std::vector<uint16_t> get_ideal_nodes(document::BucketId bucket);
    BucketOwnership check_ownership_in_pending_and_current_state(document::BucketId bucket);
};

}
