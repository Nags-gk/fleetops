package dev.nagsgk.fleetops.ingest;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.queue.TelemetryQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates heartbeats at the edge of the system and hands valid ones to the queue. */
public final class IngestService {
    public static final int MAX_BATCH = 500;

    public record Rejected(int index, String robotId, List<String> errors) {}

    public record Result(int accepted, List<Rejected> rejected, int enqueueFailures) {}

    private final TelemetryQueue queue;

    public IngestService(TelemetryQueue queue) {
        this.queue = queue;
    }

    public Result ingest(List<Heartbeat> batch) {
        if (batch.size() > MAX_BATCH) {
            throw new IllegalArgumentException("batch of " + batch.size() + " exceeds limit " + MAX_BATCH);
        }
        var ok = new ArrayList<Heartbeat>();
        var bad = new ArrayList<Rejected>();
        for (int i = 0; i < batch.size(); i++) {
            var hb = batch.get(i);
            var errs = hb == null ? List.of("null heartbeat") : hb.validate();
            if (errs.isEmpty()) ok.add(hb);
            else bad.add(new Rejected(i, hb == null ? null : hb.robotId(), errs));
        }
        var failed = ok.isEmpty() ? List.<Heartbeat>of() : queue.send(ok);
        return new Result(ok.size() - failed.size(), bad, failed.size());
    }

    public static Map<String, Object> toBody(Result r) {
        return Map.of("accepted", r.accepted(), "rejected", r.rejected(), "enqueueFailures", r.enqueueFailures());
    }
}
