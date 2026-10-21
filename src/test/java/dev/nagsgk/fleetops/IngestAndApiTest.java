package dev.nagsgk.fleetops;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import dev.nagsgk.fleetops.api.IngestHandler;
import dev.nagsgk.fleetops.api.QueryHandler;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.ingest.IngestService;
import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.store.InMemoryFleetStore;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.util.Metrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.nagsgk.fleetops.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestAndApiTest {
    final List<Heartbeat> queued = new ArrayList<>();
    final IngestService ingest = new IngestService(b -> {
        queued.addAll(b);
        return List.of();
    });

    APIGatewayV2HTTPEvent post(String body, String key) {
        return APIGatewayV2HTTPEvent.builder().withBody(body).withIsBase64Encoded(false)
                .withHeaders(key == null ? Map.of() : Map.of("x-fleet-key", key)).build();
    }

    @Test
    void validatesAndEnqueuesOnlyGoodHeartbeats() {
        var bad = new Heartbeat("bad id!", 1, 5, "S", "FLYING", 0, 0, 120, 40, 2, 0, null);
        var r = ingest.ingest(List.of(moving("A", 1, 50, 3), bad));
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).singleElement().satisfies(x -> assertThat(x.errors()).hasSize(3));
        assertThat(queued).hasSize(1);
        assertThatThrownBy(() -> ingest.ingest(java.util.Collections.nCopies(501, moving("A", 1, 50, 3))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ingestHandlerEnforcesKeyAndStatusCodes() {
        var h = new IngestHandler(ingest, "s3cret");
        var body = Json.write(Map.of("heartbeats", List.of(moving("A", 1, 50, 3))));
        assertThat(h.handleRequest(post(body, null), null).getStatusCode()).isEqualTo(401);
        assertThat(h.handleRequest(post(body, "wrong"), null).getStatusCode()).isEqualTo(401);
        assertThat(h.handleRequest(post(body, "s3cret"), null).getStatusCode()).isEqualTo(202);
        assertThat(h.handleRequest(post("{oops", "s3cret"), null).getStatusCode()).isEqualTo(400);
        assertThat(h.handleRequest(post("{\"heartbeats\":[]}", "s3cret"), null).getStatusCode()).isEqualTo(400);
    }

    @Test
    void queryApiServesFleetIncidentsAndTriage() {
        var store = new InMemoryFleetStore();
        var proc = new ProcessorService(store, i -> {}, new AnomalyDetector(), () -> 0L);
        var m = new Metrics("t");
        for (int i = 1; i <= 5; i++) proc.handle(moving("A", i, 50, 3), m);
        proc.handle(moving("B", 1, 91, 3), m);
        var triage = new TriageService(store, RunbookIndex.fromClasspath("/runbooks/drive-units.md"), null);
        long now = 1_000_000L + 6_000;
        var log = new java.util.ArrayList<String>();
        var q = new QueryHandler(store, triage, () -> now, dev.nagsgk.fleetops.auth.SecretKey.fixed("op-key"), log::add);
        var fleet = q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("GET /fleet").build(), null);
        assertThat(fleet.getStatusCode()).isEqualTo(200);
        assertThat(fleet.getBody()).contains("\"robots\":2").contains("\"openIncidents\":1");
        var inc = store.listIncidents(5).get(0);
        var t = q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("POST /incidents/{id}/triage")
                .withHeaders(Map.of("x-operator-key", "op-key")).withPathParameters(Map.of("id", inc.id())).build(), null);
        assertThat(t.getStatusCode()).isEqualTo(200);
        assertThat(t.getBody()).contains("MOTOR_OVERTEMP").contains("runbook-retrieval");
        assertThat(store.getIncident(inc.id()).orElseThrow().triage()).contains("maintenance lane");
        var r = q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("POST /incidents/{id}/resolve")
                .withHeaders(Map.of("x-operator-key", "op-key")).withPathParameters(Map.of("id", inc.id())).build(), null);
        assertThat(r.getBody()).contains("RESOLVED");
        assertThat(q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("GET /nope").build(), null).getStatusCode()).isEqualTo(404);
    }
}
