// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.node;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.UnsignedBytes;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.persistence.NameResolver;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This handles IP address configuration and allocation.
 *
 * @author mpolden
 */
public class IP {

    /** Comparator for sorting IP addresses by their natural order */
    public static final Comparator<String> NATURAL_ORDER = (ip1, ip2) -> {
        byte[] address1 = InetAddresses.forString(ip1).getAddress();
        byte[] address2 = InetAddresses.forString(ip2).getAddress();

        // IPv4 always sorts before IPv6
        if (address1.length < address2.length) return -1;
        if (address1.length > address2.length) return 1;

        // Compare each octet
        for (int i = 0; i < address1.length; i++) {
            int b1 = UnsignedBytes.toInt(address1[i]);
            int b2 = UnsignedBytes.toInt(address2[i]);
            if (b1 == b2) {
                continue;
            }
            if (b1 < b2) {
                return -1;
            } else {
                return 1;
            }
        }
        return 0;
    };

    /** IP configuration of a node */
    public static class Config {

        public static final Config EMPTY = new Config(Set.of(), Set.of());

        private final Set<String> primary;
        private final Pool pool;

        /** DO NOT USE in non-test code. Public for serialization purposes. */
        public Config(Set<String> primary, Set<String> pool) {
            this.primary = ImmutableSet.copyOf(Objects.requireNonNull(primary, "primary must be non-null"));
            this.pool = new Pool(Objects.requireNonNull(pool, "pool must be non-null"));
        }

        /** The primary addresses of this. These addresses are used when communicating with the node itself */
        public Set<String> primary() {
            return primary;
        }

        /** Returns the IP address pool available on a node */
        public Pool pool() {
            return pool;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Config config = (Config) o;
            return primary.equals(config.primary) &&
                   pool.equals(config.pool);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primary, pool);
        }

        @Override
        public String toString() {
            return String.format("ip config primary=%s pool=%s", primary, pool.asSet());
        }

        /** Validates and returns the given addresses */
        public static Set<String> require(Set<String> addresses) {
            try {
                addresses.forEach(InetAddresses::forString);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("A node must have at least one valid IP address", e);
            }
            return addresses;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {

            private Set<String> primary;
            private Set<String> pool = Set.of();
            private LockedNodeList nodes;
            private List<Node> transientNodes = List.of();

            private Builder() {}

            /** Set the primary IP address of this config */
            public Builder primary(Set<String> primary) {
                this.primary = primary;
                return this;
            }

            /** Set the pool of this config */
            public Builder pool(Set<String> pool) {
                this.pool = pool;
                return this;
            }

            /** Set the locked node list to consider when creating this config */
            public Builder lockedNodes(LockedNodeList nodes) {
                this.nodes = nodes;
                return this;
            }

            /** Set transient nodes to consider when creating this config */
            public Builder transientNodes(List<Node> transientNodes) {
                this.transientNodes = transientNodes;
                return this;
            }

            /** Build config assigned to given host and node type */
            public IP.Config assignTo(String hostname, NodeType nodeType) {
                Objects.requireNonNull(hostname, "hostname must be non-null");
                Objects.requireNonNull(nodeType, "nodeType must be non-null");
                Objects.requireNonNull(primary, "primary must be non-null");
                Objects.requireNonNull(pool, "pool must be non-null");
                Objects.requireNonNull(nodes, "nodes must be non-null");
                Objects.requireNonNull(transientNodes, "transientNodes must be non-null");

                List<Node> allNodes = new ArrayList<>(nodes.asList());
                allNodes.addAll(transientNodes);
                for (Node other : allNodes) {
                    Set<String> addresses = new HashSet<>(primary);
                    Set<String> otherAddresses = new HashSet<>(other.ipConfig().primary());

                    if (nodeType.isDockerHost()) { // Addresses of a host can never overlap with any other nodes
                        addresses.addAll(pool);
                        otherAddresses.addAll(other.ipConfig().pool().asSet());
                    }

                    otherAddresses.retainAll(addresses);

                    if (!otherAddresses.isEmpty())
                        throw new IllegalArgumentException("Cannot assign " + addresses + " to " + hostname + ": " +
                                                           otherAddresses + " already assigned to " +
                                                           other.hostname());
                }
                return new Config(primary, pool);
            }
        }

    }

    /** A pool of IP addresses. Addresses in this are destined for use by Docker containers */
    public static class Pool {

        private final Set<String> addresses;

        private Pool(Set<String> addresses) {
            this.addresses = ImmutableSet.copyOf(Objects.requireNonNull(addresses, "addresses must be non-null"));
        }

        /**
         * Find a free allocation in this pool. Note that the allocation is not final until it is assigned to a node
         *
         * @param nodes A locked list of all nodes in the repository
         * @return An allocation from the pool, if any can be made
         */
        public Optional<Allocation> findAllocation(LockedNodeList nodes, NameResolver resolver) {
            var unusedAddresses = findUnused(nodes);
            var allocation = unusedAddresses.stream()
                                            .filter(IP::isV6)
                                            .findFirst()
                                            .map(addr -> Allocation.resolveFrom(addr, resolver));
            allocation.flatMap(Allocation::ipv4Address).ifPresent(ipv4Address -> {
                if (!unusedAddresses.contains(ipv4Address)) {
                    throw new IllegalArgumentException("Allocation resolved " + ipv4Address + " from hostname " +
                                                       allocation.get().hostname +
                                                       ", but that address is not owned by this node");
                }
            });
            return allocation;
        }

        /**
         * Finds all unused addresses in this pool
         *
         * @param nodes Locked list of all nodes in the repository
         */
        public Set<String> findUnused(LockedNodeList nodes) {
            var unusedAddresses = new LinkedHashSet<>(addresses);
            nodes.filter(node -> node.ipConfig().primary().stream().anyMatch(addresses::contains))
                 .forEach(node -> unusedAddresses.removeAll(node.ipConfig().primary()));
            return Collections.unmodifiableSet(unusedAddresses);
        }

        public Set<String> asSet() {
            return addresses;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pool that = (Pool) o;
            return Objects.equals(addresses, that.addresses);
        }

        @Override
        public int hashCode() {
            return Objects.hash(addresses);
        }

        /** Validates and returns the given IP address pool */
        public static Set<String> require(Set<String> pool) {
            long ipv6AddrCount = pool.stream().filter(IP::isV6).count();
            if (ipv6AddrCount == pool.size()) {
                return pool; // IPv6-only pool is valid
            }

            long ipv4AddrCount = pool.stream().filter(IP::isV4).count();
            if (ipv4AddrCount == ipv6AddrCount) {
                return pool;
            }

            throw new IllegalArgumentException(String.format("Dual-stacked IP address list must have an " +
                                                             "equal number of addresses of each version " +
                                                             "[IPv6 address count = %d, IPv4 address count = %d]",
                                                             ipv6AddrCount, ipv4AddrCount));
        }

    }

    /** An IP address allocation from a pool */
    public static class Allocation {

        private final String hostname;
        private final String ipv6Address;
        private final Optional<String> ipv4Address;

        private Allocation(String hostname, String ipv6Address, Optional<String> ipv4Address) {
            Objects.requireNonNull(ipv6Address, "ipv6Address must be non-null");
            if (!isV6(ipv6Address)) {
                throw new IllegalArgumentException("Invalid IPv6 address '" + ipv6Address + "'");
            }

            Objects.requireNonNull(ipv4Address, "ipv4Address must be non-null");
            if (ipv4Address.isPresent() && !isV4(ipv4Address.get())) {
                throw new IllegalArgumentException("Invalid IPv4 address '" + ipv4Address + "'");
            }
            this.hostname = Objects.requireNonNull(hostname, "hostname must be non-null");
            this.ipv6Address = ipv6Address;
            this.ipv4Address = ipv4Address;
        }

        /**
         * Resolve the IP addresses and hostname of this allocation
         *
         * @param ipv6Address Unassigned IPv6 address
         * @param resolver DNS name resolver to use
         * @throws IllegalArgumentException if DNS is misconfigured
         * @return The allocation containing 1 IPv6 address and 1 IPv4 address (if hostname is dual-stack)
         */
        public static Allocation resolveFrom(String ipv6Address, NameResolver resolver) {
            String hostname6 = resolver.getHostname(ipv6Address).orElseThrow(() -> new IllegalArgumentException("Could not resolve IP address: " + ipv6Address));
            List<String> ipv4Addresses = resolver.getAllByNameOrThrow(hostname6).stream()
                                                 .filter(IP::isV4)
                                                 .collect(Collectors.toList());
            if (ipv4Addresses.size() > 1) {
                throw new IllegalArgumentException("Hostname " + hostname6 + " resolved to more than 1 IPv4 address: " + ipv4Addresses);
            }
            Optional<String> ipv4Address = ipv4Addresses.stream().findFirst();
            ipv4Address.ifPresent(addr -> {
                String hostname4 = resolver.getHostname(addr).orElseThrow(() -> new IllegalArgumentException("Could not resolve IP address: " + addr));
                if (!hostname6.equals(hostname4)) {
                    throw new IllegalArgumentException(String.format("Hostnames resolved from each IP address do not " +
                                                                     "point to the same hostname [%s -> %s, %s -> %s]",
                                                                     ipv6Address, hostname6, addr, hostname4));
                }
            });
            return new Allocation(hostname6, ipv6Address, ipv4Address);
        }

        /** Hostname pointing to the IP addresses in this */
        public String hostname() {
            return hostname;
        }

        /** IPv6 address in this allocation */
        public String ipv6Address() {
            return ipv6Address;
        }

        /** IPv4 address in this allocation */
        public Optional<String> ipv4Address() {
            return ipv4Address;
        }

        /** All IP addresses in this */
        public Set<String> addresses() {
            ImmutableSet.Builder<String> builder = ImmutableSet.builder();
            ipv4Address.ifPresent(builder::add);
            builder.add(ipv6Address);
            return builder.build();
        }

        @Override
        public String toString() {
            return "ipv6Address='" + ipv6Address + '\'' +
                   ", ipv4Address='" + ipv4Address.orElse("none") + '\'';
        }

    }

    /** Returns whether given string is an IPv4 address */
    public static boolean isV4(String ipAddress) {
        return InetAddresses.forString(ipAddress) instanceof Inet4Address;
    }

    /** Returns whether given string is an IPv6 address */
    public static boolean isV6(String ipAddress) {
        return InetAddresses.forString(ipAddress) instanceof Inet6Address;
    }

}
