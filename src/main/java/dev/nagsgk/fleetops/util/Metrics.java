package dev.nagsgk.fleetops.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CloudWatch Embedded Metric Format: one structured log line becomes custom metrics without any
 * PutMetricData calls (no extra latency or API cost on the hot path).
 */
public final class Metrics {
    private final String namespace;
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Map<String, String> units = new LinkedHashMap<>();

    public Metrics(String namespace) {
        this.namespace = namespace;
    }

    public Metrics put(String name, double v, String unit) {
        values.merge(name, v, Double::sum);
        units.put(name, unit);
        return this;
    }

    public String toEmf(String function) {
        var metrics = units.entrySet().stream().map(e -> Map.of("Name", e.getKey(), "Unit", e.getValue())).toList();
        var root = new LinkedHashMap<String, Object>();
        root.put("_aws", Map.of("Timestamp", System.currentTimeMillis(), "CloudWatchMetrics",
                java.util.List.of(Map.of("Namespace", namespace, "Dimensions", java.util.List.of(java.util.List.of("Function")),
                        "Metrics", metrics))));
        root.put("Function", function);
        root.putAll(values);
        return Json.write(root);
    }

    public void flush(String function) {
        if (!values.isEmpty()) System.out.println(toEmf(function));
    }

    public Map<String, Double> values() {
        return values;
    }
}
