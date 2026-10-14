package dev.nagsgk.fleetops.queue;

import dev.nagsgk.fleetops.model.Heartbeat;

import java.util.List;

public interface TelemetryQueue {
    /** Enqueue heartbeats; returns the ones that could not be enqueued (caller retries or reports). */
    List<Heartbeat> send(List<Heartbeat> batch);
}
