package dev.nagsgk.fleetops.process;

import dev.nagsgk.fleetops.alert.Alerter;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.model.Anomaly;
import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.model.Severity;
import dev.nagsgk.fleetops.store.FleetStore;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.util.Metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Consumes queued heartbeats: update robot state (idempotently), store the time series, run the
 * detector, open or bump incidents, and page on-call for new SEV1/SEV2 incidents only.
 *
 * Failures are reported per message so the queue redelivers only what failed (partial batch
 * response); a message that keeps failing goes to the dead-letter queue.
 */
public final class ProcessorService {
    public record Message(String id, String body) {}

    private final FleetStore store;
    private final Alerter alerter;
    private final AnomalyDetector detector;
    private final LongSupplier clock;

    public ProcessorService(FleetStore store, Alerter alerter, AnomalyDetector detector, LongSupplier clock) {
        this.store = store;
        this.alerter = alerter;
        this.detector = detector;
        this.clock = clock;
    }

    /** @return ids of messages that failed and must be retried. */
    public List<String> process(List<Message> messages, Metrics metrics) {
        var failed = new ArrayList<String>();
        for (var m : messages) {
            try {
                handle(Json.read(m.body(), Heartbeat.class), metrics);
            } catch (IllegalArgumentException poison) {
                // malformed payload will never succeed: count it and let it go (do not block the group)
                metrics.put("PoisonMessages", 1, "Count");
                System.err.println("dropping malformed message " + m.id() + ": " + poison.getMessage());
            } catch (RuntimeException e) {
                metrics.put("ProcessingErrors", 1, "Count");
                System.err.println("retrying message " + m.id() + ": " + e);
                failed.add(m.id());
            }
        }
        return failed;
    }

    public List<Anomaly> handle(Heartbeat hb, Metrics metrics) {
        var prev = store.getState(hb.robotId()).orElse(null);
        if (prev != null && prev.last().seq() >= hb.seq()) {
            metrics.put("DuplicatesDropped", 1, "Count");
            return List.of();
        }
        var r = detector.observe(prev, hb);
        if (!store.putStateIfNewer(r.state())) {
            metrics.put("DuplicatesDropped", 1, "Count");
            return List.of();
        }
        store.appendTelemetry(hb);
        metrics.put("HeartbeatsProcessed", 1, "Count");
        metrics.put("IngestLagMs", Math.max(0, clock.getAsLong() - hb.tsMillis()), "Milliseconds");
        for (var a : r.anomalies()) {
            metrics.put("Anomalies", 1, "Count");
            var open = store.openIncident(a.robotId(), a.kind());
            if (open.isPresent()) {
                store.putIncident(open.get().withSeen(a.tsMillis(), a.detail()));
            } else {
                var inc = new Incident(UUID.randomUUID().toString(), a.robotId(), a.kind(), a.severity(), "OPEN",
                        a.detail(), a.tsMillis(), a.tsMillis(), 1, null);
                store.putIncident(inc);
                metrics.put("IncidentsOpened", 1, "Count");
                if (a.severity() != Severity.SEV3) {
                    alerter.page(inc);
                    metrics.put("Pages", 1, "Count");
                }
            }
        }
        return r.anomalies();
    }
}
