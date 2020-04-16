// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.core.config;

import com.yahoo.collections.PredicateSplit;
import com.yahoo.config.FileReference;
import com.yahoo.container.Container;
import com.yahoo.filedistribution.fileacquirer.FileAcquirer;
import com.yahoo.osgi.Osgi;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.collections.PredicateSplit.partition;
import static com.yahoo.container.core.BundleLoaderProperties.DISK_BUNDLE_PREFIX;

/**
 * Manages the set of installed 3rd-party component bundles.
 *
 * @author Tony Vaagenes
 * @author gjoranv
 */
public class BundleLoader {

    /* Map of file refs of active bundles (not scheduled for uninstall) to a list of all bundles that were installed
     * (pre-install directive) by the bundle pointed to by the file ref (including itself).
     *
     * Used to:
     * 1. Avoid installing already installed bundles. Just an optimization, installing the same bundle location is a NOP
     * 2. Start bundles (all are started every time)
     * 3. Calculate the set of bundles to uninstall
     */
    private final Map<FileReference, List<Bundle>> reference2Bundles = new LinkedHashMap<>();

    private final Logger log = Logger.getLogger(BundleLoader.class.getName());
    private final Osgi osgi;

    public BundleLoader(Osgi osgi) {
        this.osgi = osgi;
    }

    private void install(List<FileReference> references) {
        Set<FileReference> bundlesToInstall = new HashSet<>(references);

        // This is just an optimization, as installing a bundle with the same location id returns the already installed bundle.
        bundlesToInstall.removeAll(reference2Bundles.keySet());

        PredicateSplit<FileReference> bundlesToInstall_isDisk = partition(bundlesToInstall, BundleLoader::isDiskBundle);
        installBundlesFromDisk(bundlesToInstall_isDisk.trueValues);
        installBundlesFromFileDistribution(bundlesToInstall_isDisk.falseValues);

        // TODO: Remove. Bundles are also started in use()
        startBundles();
    }

    private static boolean isDiskBundle(FileReference fileReference) {
        return fileReference.value().startsWith(DISK_BUNDLE_PREFIX);
    }

    private void installBundlesFromDisk(List<FileReference> bundlesToInstall) {
        for (FileReference reference : bundlesToInstall) {
            try {
                installBundleFromDisk(reference);
            }
            catch(Exception e) {
                throw new RuntimeException("Could not install bundle '" + reference + "'", e);
            }
        }
    }

    private void installBundlesFromFileDistribution(List<FileReference> bundlesToInstall) {
        if (!bundlesToInstall.isEmpty()) {
            FileAcquirer fileAcquirer = Container.get().getFileAcquirer();
            boolean hasFileDistribution = (fileAcquirer != null);
            if (hasFileDistribution) {
                installWithFileDistribution(bundlesToInstall, fileAcquirer);
            } else {
                log.warning("Can't retrieve bundles since file distribution is disabled.");
            }
        }
    }

    private void installBundleFromDisk(FileReference reference) {
        assert(reference.value().startsWith(DISK_BUNDLE_PREFIX));
        String referenceFileName = reference.value().substring(DISK_BUNDLE_PREFIX.length());
        log.info("Installing bundle from disk with reference '" + reference.value() + "'");

        File file = new File(referenceFileName);
        if ( ! file.exists()) {
            throw new IllegalArgumentException("Reference '" + reference.value() + "' not found on disk.");
        }

        List<Bundle> bundles = osgi.install(file.getAbsolutePath());

        reference2Bundles.put(reference, bundles);
    }

    private void installWithFileDistribution(List<FileReference> bundlesToInstall, FileAcquirer fileAcquirer) {
        for (FileReference reference : bundlesToInstall) {
            try {
                log.info("Installing bundle with reference '" + reference.value() + "'");
                List<Bundle> bundles = obtainBundles(reference, fileAcquirer);
                reference2Bundles.put(reference, bundles);
            }
            catch(Exception e) {
                throw new RuntimeException("Could not install bundle '" + reference + "'", e);
            }
        }
    }

    private List<Bundle> obtainBundles(FileReference reference, FileAcquirer fileAcquirer) throws InterruptedException {
        File file = fileAcquirer.waitFor(reference, 7, TimeUnit.DAYS);
        return osgi.install(file.getAbsolutePath());
    }

    /**
     * Resolves and starts (calls the Bundles BundleActivator) all bundles. Bundle resolution must take place
     * after all bundles are installed to ensure that the framework can resolve dependencies between bundles.
     */
    private void startBundles() {
        for (List<Bundle> bundles : reference2Bundles.values()) {
            for (Bundle bundle : bundles) {
                try {
                    if ( ! isFragment(bundle))
                        bundle.start();  // NOP for already ACTIVE bundles
                } catch(Exception e) {
                    throw new RuntimeException("Could not start bundle '" + bundle.getSymbolicName() + "'", e);
                }
            }
        }
    }

    private boolean isFragment(Bundle bundle) {
        BundleRevision bundleRevision = bundle.adapt(BundleRevision.class);
        if (bundleRevision == null)
            throw new NullPointerException("Null bundle revision means that bundle has probably been uninstalled: " +
                                           bundle.getSymbolicName() + ":" + bundle.getVersion());
        return (bundleRevision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0;
    }

    /**
     * Returns the bundles that are not assumed to be retained by the new application generation.
     * Note that at this point we don't yet know the full set of new bundles, because of the potential
     * pre-install directives in the new bundles. However, only "disk bundles" (file:) can be listed
     * in the pre-install directive, so we know about all the obsolete application bundles.
     */
    private Set<Bundle> getObsoleteBundles(List<FileReference> newReferences) {
        Set<Bundle> bundlesToRemove = new HashSet<>(osgi.getCurrentBundles());

        for (FileReference fileReferenceToKeep : newReferences) {
            if (reference2Bundles.containsKey(fileReferenceToKeep)) {
                bundlesToRemove.removeAll(reference2Bundles.get(fileReferenceToKeep));
            }
        }
        bundlesToRemove.removeAll(osgi.getInitialBundles());
        return bundlesToRemove;
    }

    /**
     * Cleans up the map of active file references
     */
    private void removeInactiveFileReferences(List<FileReference> newReferences) {
        // Clean up the map of active bundles
        Set<FileReference> fileReferencesToRemove = getObsoleteFileReferences(newReferences);
        fileReferencesToRemove.forEach(reference2Bundles::remove);
    }


    /**
     * Allow duplicates (bsn+version) for each bundle that corresponds to obsolete file references,
     * and avoid allowing duplicates for bundles that were installed via the
     * X-JDisc-Preinstall-Bundle directive. These bundles are always "disk bundles" (library
     * bundles installed on the node, and not transferred via file distribution).
     * Such bundles will never have duplicates because they always have the same location id.
     */
    private void allowDuplicateBundles(List<FileReference> newReferences) {
        Set<FileReference> obsoleteReferences = getObsoleteFileReferences(newReferences);

        // The bundle at index 0 for each file reference always corresponds to the bundle at the file reference location
        Set<Bundle> allowedDuplicates = obsoleteReferences.stream()
                .filter(reference -> ! isDiskBundle(reference))
                .map(reference -> reference2Bundles.get(reference).get(0))
                .collect(Collectors.toSet());

        log.info(() -> allowedDuplicates.isEmpty() ? "" : "Adding bundles to allowed duplicates: " + allowedDuplicates);
        osgi.allowDuplicateBundles(allowedDuplicates);
    }

    private Set<FileReference> getObsoleteFileReferences(List<FileReference> newReferences) {
        Set<FileReference> obsoleteReferences = new HashSet<>(reference2Bundles.keySet());
        obsoleteReferences.removeAll(newReferences);
        return obsoleteReferences;
    }

    private Set<Bundle> allActiveBundles() {
        return reference2Bundles.keySet().stream()
                .flatMap(reference -> reference2Bundles.get(reference).stream())
                .collect(Collectors.toSet());
    }

    /**
     * Installs the given set of bundles and returns the set of bundles that is no longer used
     * by the application, and should therefore be scheduled for uninstall.
     */
    public synchronized Set<Bundle> use(List<FileReference> newBundles) {
        // Must be done before allowing duplicates because allowed duplicates affect osgi.getCurrentBundles
        Set<Bundle> bundlesToUninstall = getObsoleteBundles(newBundles);

        allowDuplicateBundles(newBundles);
        removeInactiveFileReferences(newBundles);

        install(newBundles);
        startBundles();

        bundlesToUninstall.removeAll(allActiveBundles());
        log.info("Bundles to schedule for uninstall: " + bundlesToUninstall);

        log.info(installedBundlesMessage());
        return bundlesToUninstall;
    }

    private String installedBundlesMessage() {
        StringBuilder sb = new StringBuilder("Installed bundles: {" );
        for (Bundle b : osgi.getBundles())
            sb.append("[" + b.getBundleId() + "]" + b.getSymbolicName() + ":" + b.getVersion() + ", ");
        sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }

}
