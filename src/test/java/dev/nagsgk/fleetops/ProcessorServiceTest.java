package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.process.ProcessorService.Message;
import dev.nagsgk.fleetops.store.InMemoryFleetStore;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.util.Metrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.nagsgk.fleetops.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessorServiceTest {
    final InMemoryFleetStore store = new InMemoryFleetStore();
    final List<Incident> pages = new ArrayList<>();
    final ProcessorService svc = new ProcessorService(store, pages::add, new AnomalyDetector(), () -> 2_000_000L);

    Message msg(String id, Object hb) {
        return new Message(id, Json.write(hb));
    }

    @Test
    void duplicateAndOutOfOrderHeartbeatsAreIgnored() {
        var m = new Metrics("t");
        svc.process(List.of(msg("1", moving("A", 5, 50, 3)), msg("2", moving("A", 5, 50, 3)), msg("3", moving("A", 4, 50, 3))), m);
        assertThat(store.getState("A").orElseThrow().last().seq()).isEqualTo(5);
        assertThat(m.values().get("DuplicatesDropped")).isEqualTo(2.0);
        assertThat(store.telemetry("A", 0, 100)).hasSize(1);
    }

    @Test
    void repeatedAnomaliesFoldIntoOneIncidentAndOnePage() {
        var m = new Metrics("t");
        for (int i = 1; i <= 10; i++) svc.handle(moving("A", i, 92, 3), m);
        var inc = store.listIncidents(10);
        assertThat(inc).hasSize(1);
        assertThat(inc.get(0).occurrences()).isEqualTo(10);
        assertThat(pages).hasSize(1);
    }

    @Test
    void sev3OpensATicketWithoutPaging() {
        var m = new Metrics("t");
        svc.handle(hb("A", 1, "FAULT", 40, 0, 0, 50, "W301"), m);
        assertThat(store.listIncidents(10)).hasSize(1);
        assertThat(pages).isEmpty();
    }

    @Test
    void poisonMessagesAreDroppedAndTransientFailuresRetried() {
        var flaky = new InMemoryFleetStore() {
            @Override
            public void appendTelemetry(dev.nagsgk.fleetops.model.Heartbeat hb) {
                if (hb.robotId().equals("B")) throw new IllegalStateException("throttled");
                super.appendTelemetry(hb);
            }
        };
        var p = new ProcessorService(flaky, pages::add, new AnomalyDetector(), () -> 0L);
        var m = new Metrics("t");
        var failed = p.process(List.of(new Message("bad", "{not json"), msg("ok", moving("A", 1, 50, 3)), msg("retry", moving("B", 1, 50, 3))), m);
        assertThat(failed).containsExactly("retry");
        assertThat(m.values().get("PoisonMessages")).isEqualTo(1.0);
    }
}
