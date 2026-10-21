package dev.nagsgk.fleetops;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import dev.nagsgk.fleetops.api.IngestHandler;
import dev.nagsgk.fleetops.api.QueryHandler;
import dev.nagsgk.fleetops.auth.SecretKey;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.ingest.IngestService;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.store.InMemoryFleetStore;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import dev.nagsgk.fleetops.util.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.nagsgk.fleetops.Fixtures.moving;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for the ThreatLens finding TL-E04 / TL-S01: unauthenticated incident writes. */
class WriteAuthTest {
    InMemoryFleetStore store;
    List<String> audit;
    Incident inc;

    @BeforeEach
    void setUp() {
        store = new InMemoryFleetStore();
        var proc = new ProcessorService(store, i -> {}, new AnomalyDetector(), () -> 0L);
        for (int i = 1; i <= 5; i++) proc.handle(moving("A", i, 50, 3), new Metrics("t"));
        proc.handle(moving("B", 1, 91, 3), new Metrics("t"));
        audit = new ArrayList<>();
        inc = store.listIncidents(5).get(0);
    }

    QueryHandler handler(SecretKey key) {
        var triage = new TriageService(store, RunbookIndex.fromClasspath("/runbooks/drive-units.md"), null);
        return new QueryHandler(store, triage, () -> 1_006_000L, key, audit::add);
    }

    APIGatewayV2HTTPEvent write(String route, String key) {
        var headers = new HashMap<String, String>();
        if (key != null) headers.put("x-operator-key", key);
        var ctx = new APIGatewayV2HTTPEvent.RequestContext();
        var http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setSourceIp("198.51.100.7");
        http.setUserAgent("curl/8");
        ctx.setHttp(http);
        ctx.setRequestId("req-1");
        return APIGatewayV2HTTPEvent.builder().withRouteKey(route).withHeaders(headers)
                .withPathParameters(Map.of("id", inc.id())).withRequestContext(ctx).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /incidents/{id}/resolve", "POST /incidents/{id}/triage"})
    void writesWithoutTheKeyAreRejectedAndChangeNothing(String route) {
        var q = handler(SecretKey.fixed("op-key"));
        for (String bad : new String[]{null, "", "wrong", "op-key ", "OP-KEY"}) {
            assertThat(q.handleRequest(write(route, bad), null).getStatusCode()).isEqualTo(401);
        }
        var after = store.getIncident(inc.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("OPEN");
        assertThat(after.triage()).isNull();
        assertThat(audit).hasSize(5).allMatch(a -> a.contains("\"outcome\":\"denied\"") && a.contains("198.51.100.7"));
    }

    @Test
    void theCorrectKeyIsAllowedAndAudited() {
        var q = handler(SecretKey.fixed("op-key"));
        var r = q.handleRequest(write("POST /incidents/{id}/resolve", "op-key"), null);
        assertThat(r.getStatusCode()).isEqualTo(200);
        assertThat(store.getIncident(inc.id()).orElseThrow().status()).isEqualTo("RESOLVED");
        assertThat(audit).singleElement().satisfies(a -> assertThat(a).contains("\"outcome\":\"allowed\"").contains(inc.id()).doesNotContain("op-key"));
    }

    @Test
    void writesFailClosedWhenNoKeyIsConfigured() {
        var q = handler(null);
        assertThat(q.handleRequest(write("POST /incidents/{id}/resolve", "anything"), null).getStatusCode()).isEqualTo(401);
        assertThat(store.getIncident(inc.id()).orElseThrow().status()).isEqualTo("OPEN");
    }

    @Test
    void readsStayPublic() {
        var q = handler(SecretKey.fixed("op-key"));
        assertThat(q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("GET /fleet").build(), null).getStatusCode()).isEqualTo(200);
        assertThat(q.handleRequest(APIGatewayV2HTTPEvent.builder().withRouteKey("GET /incidents").build(), null).getStatusCode()).isEqualTo(200);
        assertThat(audit).isEmpty();
    }

    @Test
    void secretIsFetchedLazilyCachedAndPicksUpRotation() {
        var calls = new AtomicInteger();
        var value = new String[]{"v1"};
        var now = new AtomicLong(0);
        var key = new SecretKey(() -> { calls.incrementAndGet(); return value[0]; }, now::get, 300_000);
        assertThat(calls.get()).isZero();               // nothing fetched at init (SnapStart-safe)
        assertThat(key.matches("v1")).isTrue();
        assertThat(key.matches("v1")).isTrue();
        assertThat(calls.get()).isEqualTo(1);           // cached
        value[0] = "v2";
        now.set(300_001);
        assertThat(key.matches("v2")).isTrue();         // rotation picked up after the TTL
        assertThat(key.matches("v1")).isFalse();
    }

    @Test
    void secretOutageKeepsLastGoodValueAndFailsClosedWithoutOne() {
        var now = new AtomicLong(0);
        var fail = new boolean[]{false};
        var key = new SecretKey(() -> { if (fail[0]) throw new RuntimeException("throttled"); return "v1"; }, now::get, 10);
        assertThat(key.matches("v1")).isTrue();
        fail[0] = true;
        now.set(100);
        assertThat(key.matches("v1")).isTrue();
        var never = new SecretKey(() -> { throw new RuntimeException("down"); }, now::get, 10);
        assertThat(never.matches("anything")).isFalse();
    }

    @Test
    void ingestFailsClosedWithoutAKey() {
        var h = new IngestHandler(new IngestService(batch -> List.of()), (SecretKey) null);
        var ev = APIGatewayV2HTTPEvent.builder().withRouteKey("POST /telemetry")
                .withHeaders(Map.of("x-fleet-key", "anything")).withBody("{\"heartbeats\":[]}").withIsBase64Encoded(false).build();
        assertThat(h.handleRequest(ev, null).getStatusCode()).isEqualTo(401);
    }
}
