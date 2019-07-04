// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.google.inject.Inject;
import com.yahoo.config.provisioning.FlavorsConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * All the flavors *configured* in this zone (i.e this should be called HostFlavors).
 *
 * @author bratseth
 */
public class NodeFlavors {

    /** Flavors which are configured in this zone */
    private final Map<String, Flavor> configuredFlavors;

    @Inject
    public NodeFlavors(FlavorsConfig config) {
        HashMap<String, Flavor> b = new HashMap<>();
        for (Flavor flavor : toFlavors(config))
            b.put(flavor.name(), flavor);
        this.configuredFlavors = Collections.unmodifiableMap(b);
    }

    public List<Flavor> getFlavors() {
        return new ArrayList<>(configuredFlavors.values());
    }

    /** Returns a flavor by name, or empty if there is no flavor with this name and it cannot be created on the fly. */
    public Optional<Flavor> getFlavor(String name) {
        if (configuredFlavors.containsKey(name))
            return Optional.of(configuredFlavors.get(name));

        NodeResources nodeResources = NodeResources.fromLegacyName(name);
        if (nodeResources.allocateByLegacyName())
            return Optional.empty();
        else
            return Optional.of(new Flavor(nodeResources));
    }

    /**
     * Returns the flavor with the given name or throws an IllegalArgumentException if it does not exist
     * and cannot be created on the fly.
     */
    public Flavor getFlavorOrThrow(String flavorName) {
        return getFlavor(flavorName).orElseThrow(() -> new IllegalArgumentException("Unknown flavor '" + flavorName +
                                                                                    "'. Flavors are " + canonicalFlavorNames()));
    }

    /** Returns true if this flavor is configured or can be created on the fly */
    public boolean exists(String flavorName) {
        return getFlavor(flavorName).isPresent();
    }

    private List<String> canonicalFlavorNames() {
        return configuredFlavors.values().stream().map(Flavor::canonicalName).distinct().sorted().collect(Collectors.toList());
    }

    private static Collection<Flavor> toFlavors(FlavorsConfig config) {
        Map<String, Flavor> flavors = new HashMap<>();
        // First pass, create all flavors, but do not include flavorReplacesConfig.
        for (FlavorsConfig.Flavor flavorConfig : config.flavor()) {
            flavors.put(flavorConfig.name(), new Flavor(flavorConfig));
        }
        // Second pass, set flavorReplacesConfig to point to correct flavor.
        for (FlavorsConfig.Flavor flavorConfig : config.flavor()) {
            Flavor flavor = flavors.get(flavorConfig.name());
            for (FlavorsConfig.Flavor.Replaces flavorReplacesConfig : flavorConfig.replaces()) {
                if (! flavors.containsKey(flavorReplacesConfig.name())) {
                    throw new IllegalStateException("Replaces for " + flavor.name() + 
                                                    " pointing to a non existing flavor: " + flavorReplacesConfig.name());
                }
                flavor.replaces().add(flavors.get(flavorReplacesConfig.name()));
            }
            flavor.freeze();
        }
        // Third pass, ensure that retired flavors have a replacement
        for (Flavor flavor : flavors.values()) {
            if (flavor.isRetired() && !hasReplacement(flavors.values(), flavor)) {
                throw new IllegalStateException(
                        String.format("Flavor '%s' is retired, but has no replacement", flavor.name())
                );
            }
        }
        return flavors.values();
    }

    private static boolean hasReplacement(Collection<Flavor> flavors, Flavor flavor) {
        return flavors.stream()
                .filter(f -> !f.equals(flavor))
                .anyMatch(f -> f.satisfies(flavor));
    }

}
