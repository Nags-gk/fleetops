package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.sim.FleetSimulator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the detector's measured quality against regressions. */
class SimulatorRegressionTest {
    @Test
    void detectorFindsInjectedFaultsWithoutFalsePages() {
        var r = FleetSimulator.evaluate(40, 40, 3).report();
        assertThat((double) r.get("recall")).isGreaterThanOrEqualTo(0.9);
        assertThat((long) r.get("false_pages")).isZero();
        assertThat((double) r.get("false_positives_per_robot_hour")).isLessThan(0.5);
    }
}
