// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.systemflags.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.config.provision.SystemName;
import com.yahoo.text.JSON;
import com.yahoo.vespa.flags.FlagId;
import com.yahoo.vespa.flags.json.FlagData;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * Represents a hierarchy of flag data files. See {@link FlagsTarget} for file naming convention.
 *
 * The flag files must reside in a 'flags/' root directory containing a directory for each flag name:
 * {@code ./flags/<flag-id>/*.json}
 *
 * @author bjorncs
 */
public class SystemFlagsDataArchive {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<FlagId, Map<String, FlagData>> files;

    private SystemFlagsDataArchive(Map<FlagId, Map<String, FlagData>> files) {
        this.files = files;
    }

    public static SystemFlagsDataArchive fromZip(InputStream rawIn) {
        Builder builder = new Builder();
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(rawIn))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("flags/")) {
                    Path filePath = Paths.get(name);
                    String rawData = new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                    addFile(builder, rawData, filePath);
                }
            }
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SystemFlagsDataArchive fromDirectory(Path directory) {
        Path root = directory.toAbsolutePath();
        Path flagsDirectory = directory.resolve("flags");
        if (!Files.isDirectory(flagsDirectory)) {
            throw new IllegalArgumentException("Sub-directory 'flags' does not exist: " + flagsDirectory);
        }
        try (Stream<Path> directoryStream = Files.walk(root)) {
            Builder builder = new Builder();
            directoryStream.forEach(absolutePath -> {
                Path relativePath = root.relativize(absolutePath);
                if (!Files.isDirectory(absolutePath) &&
                        relativePath.startsWith("flags")) {
                    String rawData = uncheck(() -> Files.readString(absolutePath, StandardCharsets.UTF_8));
                    addFile(builder, rawData, relativePath);
                }
            });
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void toZip(OutputStream out) {
        ZipOutputStream zipOut = new ZipOutputStream(out);
        files.forEach((flagId, fileMap) -> {
            fileMap.forEach((filename, flagData) -> {
                uncheck(() -> {
                    zipOut.putNextEntry(new ZipEntry(toFilePath(flagId, filename)));
                    zipOut.write(flagData.serializeToUtf8Json());
                    zipOut.closeEntry();
                });
            });
        });
        uncheck(zipOut::flush);
    }

    public Set<FlagData> flagData(FlagsTarget target) {
        List<String> filenames = target.flagDataFilesPrioritized();
        Set<FlagData> targetData = new HashSet<>();
        files.forEach((flagId, fileMap) -> {
            for (String filename : filenames) {
                FlagData data = fileMap.get(filename);
                if (data != null) {
                    if (!data.isEmpty()) {
                        targetData.add(data);
                    }
                    return;
                }
            }
        });
        return targetData;
    }

    public void validateAllFilesAreForTargets(SystemName currentSystem, Set<FlagsTarget> targets) throws IllegalArgumentException {
        Set<String> validFiles = targets.stream()
                .flatMap(target -> target.flagDataFilesPrioritized().stream())
                .collect(Collectors.toSet());
        Set<SystemName> otherSystems = Arrays.stream(SystemName.values())
                .filter(systemName -> systemName != currentSystem)
                .collect(Collectors.toSet());
        files.forEach((flagId, fileMap) -> {
            for (String filename : fileMap.keySet()) {
                boolean isFileForOtherSystem = otherSystems.stream()
                        .anyMatch(system -> filename.startsWith(system.value() + "."));
                boolean isFileForCurrentSystem = validFiles.contains(filename);
                if (!isFileForOtherSystem && !isFileForCurrentSystem) {
                    throw new IllegalArgumentException("Unknown flag file: " + toFilePath(flagId, filename));
                }
            }
        });
    }

    private static void addFile(Builder builder, String rawData, Path filePath) {
        String filename = filePath.getFileName().toString();
        if (filename.startsWith(".")) {
            return; // Ignore files starting with '.'
        }
        if (!filename.endsWith(".json")) {
            throw new IllegalArgumentException(String.format("Only JSON files are allowed in 'flags/' directory (found '%s')", filePath.toString()));
        }
        FlagId directoryDeducedFlagId = new FlagId(filePath.getName(1).toString());
        FlagData flagData;
        if (rawData.isBlank()) {
            flagData = new FlagData(directoryDeducedFlagId);
        } else {
            flagData = FlagData.deserialize(rawData);
            if (!directoryDeducedFlagId.equals(flagData.id())) {
                throw new IllegalArgumentException(
                        String.format("Flag data file with flag id '%s' in directory for '%s'",
                                flagData.id(), directoryDeducedFlagId.toString()));
            }

            String serializedData = flagData.serializeToJson();
            String normalizedRawData = removeCommentsFromJson(rawData);
            if (!JSON.equals(serializedData, normalizedRawData)) {
                throw new IllegalArgumentException(filePath + " contains unknown non-comment fields: " +
                        "after removing any comment fields the JSON is:\n  " +
                        normalizedRawData +
                        "\nbut deserializing this ended up with a JSON that are missing some of the fields:\n  " +
                        serializedData +
                        "\nSee https://git.ouroath.com/vespa/hosted-feature-flags for more info on the JSON syntax");
            }
        }
        builder.addFile(filename, flagData);
    }

    static String removeCommentsFromJson(String json) {
        JsonNode jsonNode = uncheck(() -> mapper.readTree(json));
        removeComments(jsonNode);
        return jsonNode.toString();
    }

    private static void removeComments(JsonNode node) {
        if (node instanceof ObjectNode) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.remove("comment");
        }

        node.forEach(SystemFlagsDataArchive::removeComments);
    }

    private static String toFilePath(FlagId flagId, String filename) {
        return "flags/" + flagId.toString() + "/" + filename;
    }

    public static class Builder {
        private final Map<FlagId, Map<String, FlagData>> files = new TreeMap<>();

        public Builder() {}

        public Builder addFile(String filename, FlagData data) {
            files.computeIfAbsent(data.id(), k -> new TreeMap<>()).put(filename, data);
            return this;
        }

        public SystemFlagsDataArchive build() {
            Map<FlagId, Map<String, FlagData>> copy = new TreeMap<>();
            files.forEach((flagId, map) -> copy.put(flagId, new TreeMap<>(map)));
            return new SystemFlagsDataArchive(copy);
        }

    }
}
