package dev.nagsgk.fleetops.model;

/**
 * An open operational issue. One incident per (robot, kind) while it is open: repeated anomalies
 * of the same kind bump {@code occurrences} instead of paging again (alert de-duplication).
 */
public record Incident(
        String id,
        String robotId,
        String kind,
        Severity severity,
        String status,        // OPEN | RESOLVED
        String detail,
        long openedAt,
        long lastSeenAt,
        int occurrences,
        String triage) {

    public Incident withSeen(long ts, String newDetail) {
        return new Incident(id, robotId, kind, severity, status, newDetail, openedAt, ts, occurrences + 1, triage);
    }

    public Incident withTriage(String text) {
        return new Incident(id, robotId, kind, severity, status, detail, openedAt, lastSeenAt, occurrences, text);
    }

    public Incident resolved(long ts) {
        return new Incident(id, robotId, kind, severity, "RESOLVED", detail, openedAt, ts, occurrences, triage);
    }
}
