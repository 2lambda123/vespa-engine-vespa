// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * This class generates a java class based on the vtag.map file generated by dist/getversion.pl
 */
public class VersionTagger {

    public static final String V_TAG_PKG = "V_TAG_PKG";

    VersionTagger() {}

    private static void printUsage(PrintStream out) {
        out.println("Usage: java VersionTagger vtagmap pkgname outputdir");
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage(System.err);
            throw new RuntimeException("bad arguments to main(): vtag.map packageName outputDirectory [outputFormat (simple or vtag)]");
        }
        try {
            VersionTagger me = new VersionTagger();
            me.runProgram(args);
        } catch (Exception e) {
            System.err.println(e);
            printUsage(System.err);
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> readVtagMap(String path) {
        Map<String, String> map = new HashMap<>();
        try {
            BufferedReader in = new BufferedReader(new FileReader(path));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] elements = line.split("\\s+", 2);
                map.put(elements[0], elements[1]);
            }
        } catch (FileNotFoundException e) {
            // Use default values
            map.put("V_TAG", "NOTAG");
            map.put("V_TAG_DATE", "NOTAG");
            map.put("V_TAG_PKG", "8.9999.0");
            map.put("V_TAG_ARCH", "NOTAG");
            map.put("V_TAG_SYSTEM", "NOTAG");
            map.put("V_TAG_SYSTEM_REV", "NOTAG");
            map.put("V_TAG_BUILDER", "NOTAG");
            map.put("V_TAG_COMPONENT", "8.9999.0");
            map.put("V_TAG_COMMIT_SHA", "badc0ffe");
            map.put("V_TAG_COMMIT_DATE", "0");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return map;
    }
    private enum Format {
        SIMPLE,
        VTAG
    }

    void runProgram(String[] args)  throws IOException {
        String vtagmapPath = args[0];
        String packageName = args[1];
        String dirName = args[2] + "/" + packageName.replaceAll("\\.", "/");
        Format format = args.length >= 4 ? Format.valueOf(args[3].toUpperCase()) : Format.SIMPLE;
        File outDir = new File(dirName);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("could not create directory " + outDir);
        }

        String className = format == Format.SIMPLE ? "VespaVersion" : "Vtag";
        String outFile = dirName + "/" + className +".java";
        Path outPath = Path.of(outFile);
        Path tmpPath = Path.of(outFile + ".tmp");
        var out = Files.newOutputStream(tmpPath);
        OutputStreamWriter writer = new OutputStreamWriter(out);
        System.err.println("generating: " + outFile);

        Map<String, String> vtagMap = readVtagMap(vtagmapPath);
        writer.write(String.format("package %s;\n\n", packageName));

        if (format == Format.VTAG) {
            writer.write("import java.time.Instant;\n");
            writer.write("import com.yahoo.component.Version;\n");
        }

        writer.write(String.format("\npublic class %s {\n", className));
        if (!vtagMap.containsKey(V_TAG_PKG)) {
            throw new RuntimeException("V_TAG_PKG not present in map file");
        }
        switch (format) {
            case SIMPLE:
                String version = vtagMap.get(V_TAG_PKG);
                String elements[] = version.split("\\.");
                writer.write(String.format("    public static final int major = %s;\n", elements[0]));
                writer.write(String.format("    public static final int minor = %s;\n", elements[1]));
                writer.write(String.format("    public static final int micro = %s;\n", elements[2]));
                break;
            case VTAG:
                long commitDateSecs = 0;
                for (var entry : vtagMap.entrySet()) {
                    var key = entry.getKey();
                    var value = entry.getValue();
                    try {
                        writer.write(String.format("    public static final String %s = \"%s\";\n", key, value));
                        if ("V_TAG_COMMIT_DATE".equals(key)) {
                            commitDateSecs = Long.parseLong(value);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
                writer.write("    public static final Version currentVersion = new Version(V_TAG_COMPONENT);\n");
                writer.write("    public static final String commitSha = V_TAG_COMMIT_SHA;\n");
                writer.write("    public static final Instant commitDate = Instant.ofEpochSecond(" + commitDateSecs +");\n");
                break;
        }
        writer.write("}\n");
        writer.close();
        out.close();
        if (Files.exists(outPath)) {
            byte[] tmpBytes = Files.readAllBytes(tmpPath);
            byte[] oldBytes = Files.readAllBytes(outPath);
            if (Arrays.equals(tmpBytes, oldBytes)) {
                Files.delete(tmpPath);
                return;
            }
        }
        Files.deleteIfExists(outPath);
        Files.move(tmpPath, outPath);
    }

}
