package dev.nagsgk.fleetops.detect;

import dev.nagsgk.fleetops.model.Anomaly;
import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.RobotState;
import dev.nagsgk.fleetops.model.RobotState.DetectorStats;
import dev.nagsgk.fleetops.model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming per-robot anomaly detection with O(1) state per robot.
 *
 * <ul>
 *   <li>Hard limits: critical battery, motor over-temperature, reported error codes.</li>
 *   <li>Drift: CUSUM on the motor-temperature and wheel-current residuals against this unit's own
 *       EWMA baseline. Learned only from steady MOVING samples, so load changes such as lifting a
 *       pod don't read as faults, and only after the unit has settled in that state for 30 s. The baseline stops adapting once the CUSUM passes half its limit, so a
 *       slow fault cannot teach the model that the fault is normal.</li>
 *   <li>Stuck drive: commanded MOVING with near-zero velocity for longer than a threshold.</li>
 * </ul>
 */
public final class AnomalyDetector {

    public record Config(double alpha, int warmup, double cusumSlack, double cusumLimit,
                         double tempSdFloor, double currentSdFloor, long settleMillis, double maxMotorTempC,
                         double criticalBatteryPct, long stuckMillis, double stuckVelocity) {
        public static Config defaults() {
            return new Config(0.01, 60, 0.5, 12.0, 1.5, 0.25, 30_000, 85.0, 8.0, 20_000, 0.02);
        }
    }

    public record Result(RobotState state, List<Anomaly> anomalies) {}

    private final Config cfg;

    public AnomalyDetector(Config cfg) {
        this.cfg = cfg;
    }

    public AnomalyDetector() {
        this(Config.defaults());
    }

    /** One CUSUM step; returns the new stats and whether this sample is out of control. */
    private record Step(DetectorStats stats, boolean alarm, double cusum) {}

    private Step cusum(DetectorStats st, double x, double floor) {
        if (st.n() < cfg.warmup()) return new Step(st.learn(x, cfg.alpha()), false, 0);
        double z = st.z(x, floor);
        double c = Math.max(0, st.cusum() + z - cfg.cusumSlack());
        boolean alarm = c > cfg.cusumLimit();
        // restart the statistic after signalling; repeated alarms are de-duplicated into one incident
        var next = st.withCusum(alarm ? 0 : c);
        // Keep adapting the baseline from every sample while the statistic is well below the limit.
        // (Learning only when the CUSUM is exactly zero would discard high samples and bias the mean low.)
        if (c < cfg.cusumLimit() / 2) next = next.learn(x, cfg.alpha());
        return new Step(next, alarm, c);
    }

    public Result observe(RobotState prev, Heartbeat hb) {
        RobotState st = prev == null ? RobotState.first(hb) : prev;
        var out = new ArrayList<Anomaly>();
        String id = hb.robotId();
        long ts = hb.tsMillis();

        if (hb.batteryPct() <= cfg.criticalBatteryPct() && !"CHARGING".equals(hb.state())) {
            out.add(new Anomaly(id, "BATTERY_CRITICAL", Severity.SEV2,
                    "battery %.1f%% while %s".formatted(hb.batteryPct(), hb.state()), ts));
        }
        if (hb.motorTempC() >= cfg.maxMotorTempC()) {
            out.add(new Anomaly(id, "MOTOR_OVERTEMP", Severity.SEV1,
                    "motor %.1f C exceeds %.0f C limit".formatted(hb.motorTempC(), cfg.maxMotorTempC()), ts));
        }
        for (String code : hb.errorCodes()) {
            out.add(new Anomaly(id, "ERROR_" + code, code.startsWith("E") ? Severity.SEV2 : Severity.SEV3,
                    "drive reported " + code, ts));
        }

        DetectorStats temp = st.temp(), cur = st.current();
        long stateSince = hb.state().equals(st.last().state()) ? st.stateSinceMillis() : ts;
        // thermal steady state: only judge temperature/current after MOVING has settled for a while
        boolean steady = "MOVING".equals(hb.state()) && hb.velocityMps() > cfg.stuckVelocity()
                && ts - stateSince >= cfg.settleMillis();
        if (steady) {
            var t = cusum(temp, hb.motorTempC(), cfg.tempSdFloor());
            var c = cusum(cur, hb.wheelCurrentA(), cfg.currentSdFloor());
            temp = t.stats();
            cur = c.stats();
            if (t.alarm() && hb.motorTempC() < cfg.maxMotorTempC()) {
                out.add(new Anomaly(id, "MOTOR_TEMP_DRIFT", Severity.SEV3,
                        "motor %.1f C, %.1f C above this unit's baseline %.1f C (CUSUM %.0f)"
                                .formatted(hb.motorTempC(), hb.motorTempC() - temp.mean(), temp.mean(), t.cusum()), ts));
            }
            if (c.alarm()) {
                out.add(new Anomaly(id, "WHEEL_CURRENT_DRIFT", Severity.SEV3,
                        "wheel current %.2f A vs baseline %.2f A (CUSUM %.0f)"
                                .formatted(hb.wheelCurrentA(), cur.mean(), c.cusum()), ts));
            }
        }

        long stillSince = st.movingStillSinceMillis();
        boolean still = "MOVING".equals(hb.state()) && Math.abs(hb.velocityMps()) < cfg.stuckVelocity();
        if (still) {
            if (stillSince == 0) stillSince = ts;
            if (ts - stillSince >= cfg.stuckMillis()) {
                out.add(new Anomaly(id, "DRIVE_STUCK", Severity.SEV2,
                        "commanded to move but stationary for %d s".formatted((ts - stillSince) / 1000), ts));
            }
        } else {
            stillSince = 0;
        }
        return new Result(new RobotState(hb, temp, cur, stillSince, stateSince), out);
    }
}
