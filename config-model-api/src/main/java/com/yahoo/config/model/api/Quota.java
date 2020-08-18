package com.yahoo.config.model.api;

import com.yahoo.slime.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.slime.SlimeUtils;

import java.util.Objects;
import java.util.Optional;

public class Quota {
    private final Optional<Integer> maxClusterSize;
    private final Optional<Integer> budget;

    public Quota(Optional<Integer> maybeClusterSize, Optional<Integer> budget) {
        this.maxClusterSize = maybeClusterSize;
        this.budget = budget;
    }

    public static Quota fromSlime(Inspector inspector) {
        var clusterSize = SlimeUtils.optionalLong(inspector.field("clusterSize"));
        var budget = SlimeUtils.optionalLong(inspector.field("budget"));
        return new Quota(clusterSize.map(Long::intValue), budget.map(Long::intValue));
    }

    public Slime toSlime() {
        var slime = new Slime();
        var root = slime.setObject();
        maxClusterSize.ifPresent(clusterSize -> root.setLong("clusterSize", clusterSize));
        budget.ifPresent(b -> root.setLong("budget", b));
        return slime;
    }

    public static Quota empty() {
        return new Quota(Optional.empty(), Optional.empty());
    }

    public Optional<Integer> maxClusterSize() {
        return maxClusterSize;
    }

    public Optional<Integer> budget() {
        return budget;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quota quota = (Quota) o;
        return Objects.equals(maxClusterSize, quota.maxClusterSize) &&
                Objects.equals(budget, quota.budget);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxClusterSize, budget);
    }

    @Override
    public String toString() {
        return "Quota{" +
                "maxClusterSize=" + maxClusterSize +
                ", budget=" + budget +
                '}';
    }
}
