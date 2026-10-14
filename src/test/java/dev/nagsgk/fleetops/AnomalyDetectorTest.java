package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.model.Anomaly;
import dev.nagsgk.fleetops.model.RobotState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.nagsgk.fleetops.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {
    final AnomalyDetector det = new AnomalyDetector();

    List<Anomaly> run(int n, java.util.function.IntFunction<dev.nagsgk.fleetops.model.Heartbeat> gen) {
        RobotState st = null;
        var all = new ArrayList<Anomaly>();
        for (int i = 1; i <= n; i++) {
            var r = det.observe(st, gen.apply(i));
            st = r.state();
            all.addAll(r.anomalies());
        }
        return all;
    }

    @Test
    void healthyUnitStaysQuietForAnHour() {
        var rnd = new Random(1);
        assertThat(run(3600, i -> moving("A", i, 50 + rnd.nextGaussian() * 0.5, 3 + rnd.nextGaussian() * 0.15))).isEmpty();
    }

    @Test
    void hardOverTemperatureIsSev1() {
        var a = run(5, i -> moving("A", i, 90, 3));
        assertThat(a).extracting(Anomaly::kind).contains("MOTOR_OVERTEMP");
        assertThat(a.get(0).severity().name()).isEqualTo("SEV1");
    }

    @Test
    void slowTemperatureDriftIsCaughtBeforeTheHardLimit() {
        var rnd = new Random(2);
        // 10 minutes healthy, then +0.05 C/s
        var a = run(1200, i -> moving("A", i, 50 + rnd.nextGaussian() * 0.5 + (i > 600 ? 0.05 * (i - 600) : 0), 3));
        assertThat(a).isNotEmpty();
        assertThat(a.get(0).kind()).isEqualTo("MOTOR_TEMP_DRIFT");
        long firstAt = (a.get(0).tsMillis() - 1_000_000L) / 1000;
        assertThat(firstAt).isBetween(600L, 800L);
    }

    @Test
    void stuckOnlyAfterTwentySeconds() {
        var a = run(19, i -> hb("A", i, "MOVING", 50, 3, 0, 80));
        assertThat(a).isEmpty();
        var b = run(25, i -> hb("A", i, "MOVING", 50, 3, 0, 80));
        assertThat(b).extracting(Anomaly::kind).contains("DRIVE_STUCK");
    }

    @Test
    void criticalBatteryIgnoredWhileCharging() {
        assertThat(run(3, i -> hb("A", i, "CHARGING", 40, 0, 0, 5))).isEmpty();
        assertThat(run(3, i -> hb("A", i, "MOVING", 40, 3, 1, 5))).extracting(Anomaly::kind).contains("BATTERY_CRITICAL");
    }

    @Test
    void errorCodesBecomeAnomalies() {
        assertThat(run(1, i -> hb("A", i, "FAULT", 40, 0, 0, 50, "E101"))).extracting(Anomaly::kind).containsExactly("ERROR_E101");
    }
}
