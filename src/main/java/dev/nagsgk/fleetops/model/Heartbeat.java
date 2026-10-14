package dev.nagsgk.fleetops.model;

import java.util.List;

/**
 * One telemetry sample from a drive unit. {@code seq} is a per-robot monotonically increasing
 * counter used for idempotency and out-of-order protection.
 */
public record Heartbeat(
        String robotId,
        long seq,
        long tsMillis,
        String site,
        String state,          // IDLE | MOVING | LIFTING | CHARGING | FAULT
        double x,
        double y,
        double batteryPct,
        double motorTempC,
        double wheelCurrentA,
        double velocityMps,
        List<String> errorCodes) {

    public Heartbeat {
        errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
    }

    /** Returns a list of validation problems; empty means valid. */
    public List<String> validate() {
        var errs = new java.util.ArrayList<String>();
        if (robotId == null || !robotId.matches("[A-Za-z0-9_-]{1,64}")) errs.add("robotId must match [A-Za-z0-9_-]{1,64}");
        if (seq < 0) errs.add("seq must be >= 0");
        if (tsMillis <= 0) errs.add("tsMillis must be > 0");
        if (state == null || !List.of("IDLE", "MOVING", "LIFTING", "CHARGING", "FAULT").contains(state)) errs.add("state is not a known drive state");
        if (batteryPct < 0 || batteryPct > 100) errs.add("batteryPct must be in [0, 100]");
        if (motorTempC < -40 || motorTempC > 200) errs.add("motorTempC out of sensor range");
        if (wheelCurrentA < 0 || wheelCurrentA > 100) errs.add("wheelCurrentA out of sensor range");
        if (Double.isNaN(x) || Double.isNaN(y)) errs.add("position must be numeric");
        return errs;
    }
}
