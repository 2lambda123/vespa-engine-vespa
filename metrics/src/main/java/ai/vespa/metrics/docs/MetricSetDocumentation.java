package ai.vespa.metrics.docs;

import ai.vespa.metrics.Suffix;
import ai.vespa.metrics.VespaMetrics;
import ai.vespa.metrics.set.MetricSet;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author olaa
 */
public class MetricSetDocumentation {

    protected static void writeMetricSetDocumentation(String path, String name, MetricSet metricSet, Map<String, VespaMetrics[]> metricsByType) {

        var groupedBySuffix = metricSet.getMetrics()
                .keySet()
                .stream()
                .map(MetricSetDocumentation::withSuffix)
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(LinkedHashSet::new))));

        var metricTypeByName = metricsByType.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> Arrays.stream(entry.getValue())
                                .filter(val -> groupedBySuffix.containsKey(val.baseName()))
                                .collect(Collectors.toMap(
                                        val -> val,
                                        val -> groupedBySuffix.get(val.baseName()),
                                        (a, b) -> a,
                                        LinkedHashMap::new
                                )),
                        (a, b) -> a,
                        LinkedHashMap::new));

        var referenceBuilder = new StringBuilder();
        referenceBuilder.append(String.format("""
                ---
                # Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
                title: "%s Metric Set"
                ---""", name));
        metricsByType.keySet()
                .stream()
                .sorted()
                .filter(m -> !metricTypeByName.get(m).isEmpty())
                .forEach(type ->
                        referenceBuilder.append(String.format("""

                            <h2 id="%s-metrics">%s Metrics</h2>
                            <table class="table">
                              <thead>
                                 <tr><th>Name</th><th>Description</th><th>Unit</th><th>Suffixes</th></tr>
                              </thead>
                              <tbody>
                             %s  </tbody>
                            </table>
                            """, type.toLowerCase(), type, htmlRows(metricTypeByName.get(type))))
                );
        try (FileWriter fileWriter = new FileWriter(path + "/" + metricSet.getId().toLowerCase() + "-set-metrics-reference.html")) {
            fileWriter.write(referenceBuilder.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String htmlRows(Map<VespaMetrics, LinkedHashSet<String>> metrics) {
        return metrics.entrySet()
                .stream()
                .map(entry ->
                        String.format(
                                """
                                    <tr>
                                       <td><p id="%s">%s</p></td>
                                       <td>%s</td>
                                       <td>%s</td>
                                       <td>%s</td>
                                    </tr>
                                 """,
                                entry.getKey().baseName().replaceAll("\\.", "_"),
                                entry.getKey().baseName(),
                                entry.getKey().description(),
                                entry.getKey().unit().toString().toLowerCase(),
                                String.join(", ", entry.getValue()))

                ).collect(Collectors.joining());
    }

    private static Map.Entry<String, String> withSuffix(String metricName) {
        try {
            var suffixIndex = metricName.lastIndexOf(".");
            var suffix = Suffix.valueOf(metricName.substring(suffixIndex + 1));
            return Map.entry(metricName.substring(0, suffixIndex), suffix.toString());
        } catch (Exception e) {
            return Map.entry(metricName, "N/A");
        }
    }

}