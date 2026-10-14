package dev.nagsgk.fleetops.model;

/** A single detector finding for one heartbeat. */
public record Anomaly(String robotId, String kind, Severity severity, String detail, long tsMillis) {}
