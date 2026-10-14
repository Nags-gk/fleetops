package dev.nagsgk.fleetops.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.nagsgk.fleetops.ingest.IngestService;
import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.util.Metrics;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** POST /telemetry  {"heartbeats":[...]}  (header x-fleet-key required) -> 202 */
public final class IngestHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private final IngestService svc;
    private final String key;

    public IngestHandler() {
        this(Env.ingest(), System.getenv("INGEST_KEY"));
    }

    public IngestHandler(IngestService svc, String key) {
        this.svc = svc;
        this.key = key;
    }

    record Body(List<Heartbeat> heartbeats) {}

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent ev, Context ctx) {
        var headers = ev.getHeaders() == null ? Map.<String, String>of() : ev.getHeaders();
        if (key != null && !constantTimeEquals(key, headers.get("x-fleet-key"))) {
            return Http.error(401, "missing or invalid x-fleet-key");
        }
        String raw = ev.getBody();
        if (raw == null) return Http.error(400, "empty body");
        if (ev.getIsBase64Encoded()) raw = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        var metrics = new Metrics("FleetOps");
        try {
            var body = Json.M.readValue(raw, new TypeReference<Body>() {});
            if (body.heartbeats() == null || body.heartbeats().isEmpty()) return Http.error(400, "heartbeats must be a non-empty array");
            var r = svc.ingest(body.heartbeats());
            metrics.put("HeartbeatsAccepted", r.accepted(), "Count").put("HeartbeatsRejected", r.rejected().size(), "Count");
            int status = r.enqueueFailures() > 0 ? 503 : r.accepted() == 0 ? 422 : 202;
            return Http.json(status, IngestService.toBody(r));
        } catch (IllegalArgumentException e) {
            return Http.error(413, e.getMessage());
        } catch (Exception e) {
            return Http.error(400, "body is not valid JSON");
        } finally {
            metrics.flush("ingest");
        }
    }
}
