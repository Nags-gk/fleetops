package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.model.Heartbeat;

import java.util.List;

public final class Fixtures {
    private Fixtures() {}

    public static Heartbeat hb(String id, long seq, String state, double temp, double current, double v, double battery, String... codes) {
        return new Heartbeat(id, seq, 1_000_000L + seq * 1000, "T1", state, 1, 2, battery, temp, current, v, List.of(codes));
    }

    public static Heartbeat moving(String id, long seq, double temp, double current) {
        return hb(id, seq, "MOVING", temp, current, 1.2, 80);
    }
}
