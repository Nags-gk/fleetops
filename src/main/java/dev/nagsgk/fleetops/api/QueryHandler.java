package dev.nagsgk.fleetops.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import dev.nagsgk.fleetops.auth.SecretKey;
import dev.nagsgk.fleetops.model.RobotState;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.store.FleetStore;
import dev.nagsgk.fleetops.triage.TriageService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Read API for the operations dashboard and on-call:
 * <pre>
 *   GET  /fleet                      summary + every robot's latest state
 *   GET  /robots/{id}/telemetry      last 10 minutes (or ?since=epochMillis)
 *   GET  /incidents                  newest first
 *   POST /incidents/{id}/triage      RAG triage (cached on the incident)    operator key required
 *   POST /incidents/{id}/resolve                                            operator key required
 * </pre>
 * Reads are public so the dashboard works without credentials. Writes change incident
 * state or spend model budget, so they require the {@code x-operator-key} header, which
 * is checked in constant time against a key held in Secrets Manager. Every write
 * attempt, allowed or denied, is written to the log as a structured audit record.
 */
public final class QueryHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    static final long OFFLINE_AFTER_MS = 30_000;

    private final FleetStore store;
    private final TriageService triage;
    private final LongSupplier clock;
    private final SecretKey operatorKey;
    private final Consumer<String> audit;

    static final Set<String> WRITE_ROUTES = Set.of("POST /incidents/{id}/triage", "POST /incidents/{id}/resolve");

    public QueryHandler() {
        this(Env.store(), null, System::currentTimeMillis, Env.secretKey("OPERATOR_KEY"), System.out::println);
    }

    public QueryHandler(FleetStore store, TriageService triage, LongSupplier clock, SecretKey operatorKey, Consumer<String> audit) {
        this.store = store;
        this.triage = triage == null ? Env.triage(store) : triage;
        this.clock = clock;
        this.operatorKey = operatorKey;
        this.audit = audit;
    }

    /** Audit record for a write attempt; the presented key is never logged. */
    private void audit(APIGatewayV2HTTPEvent ev, String route, String id, String outcome) {
        var http = ev.getRequestContext() == null ? null : ev.getRequestContext().getHttp();
        var rec = new LinkedHashMap<String, Object>();
        rec.put("audit", "fleetops.write");
        rec.put("ts", clock.getAsLong());
        rec.put("route", route);
        rec.put("incidentId", id);
        rec.put("outcome", outcome);
        rec.put("sourceIp", http == null ? null : http.getSourceIp());
        rec.put("userAgent", http == null ? null : http.getUserAgent());
        rec.put("requestId", ev.getRequestContext() == null ? null : ev.getRequestContext().getRequestId());
        audit.accept(Json.write(rec));
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent ev, Context ctx) {
        String route = ev.getRouteKey() == null ? "" : ev.getRouteKey();
        var p = ev.getPathParameters() == null ? Map.<String, String>of() : ev.getPathParameters();
        var q = ev.getQueryStringParameters() == null ? Map.<String, String>of() : ev.getQueryStringParameters();
        if (WRITE_ROUTES.contains(route)) {
            var headers = ev.getHeaders() == null ? Map.<String, String>of() : ev.getHeaders();
            if (operatorKey == null || !operatorKey.matches(headers.get("x-operator-key"))) {
                audit(ev, route, p.get("id"), "denied");
                return Http.error(401, "operator key required for write actions");
            }
            audit(ev, route, p.get("id"), "allowed");
        }
        try {
            return switch (route) {
                case "GET /fleet" -> Http.json(200, fleet());
                case "GET /robots/{id}/telemetry" -> {
                    long since = q.containsKey("since") ? Long.parseLong(q.get("since")) : clock.getAsLong() - 600_000;
                    yield Http.json(200, Map.of("robotId", p.get("id"), "telemetry", store.telemetry(p.get("id"), since, 600)));
                }
                case "GET /incidents" -> Http.json(200, Map.of("incidents", store.listIncidents(
                        Math.min(200, Integer.parseInt(q.getOrDefault("limit", "50"))))));
                case "POST /incidents/{id}/triage" -> store.getIncident(p.get("id")).map(inc -> {
                    var t = triage.triage(inc);
                    store.putIncident(inc.withTriage(t.text()));
                    return Http.json(200, t);
                }).orElseGet(() -> Http.error(404, "no such incident"));
                case "POST /incidents/{id}/resolve" -> store.getIncident(p.get("id"))
                        .map(inc -> {
                            var r = inc.resolved(clock.getAsLong());
                            store.putIncident(r);
                            return Http.json(200, r);
                        })
                        .orElseGet(() -> Http.error(404, "no such incident"));
                default -> Http.error(404, "unknown route " + route);
            };
        } catch (NumberFormatException e) {
            return Http.error(400, "bad numeric parameter");
        }
    }

    Map<String, Object> fleet() {
        long now = clock.getAsLong();
        var robots = store.listStates().stream().sorted(Comparator.comparing(s -> s.last().robotId())).toList();
        var byState = new LinkedHashMap<String, Integer>();
        int offline = 0;
        double battery = 0;
        for (RobotState s : robots) {
            boolean off = now - s.last().tsMillis() > OFFLINE_AFTER_MS;
            if (off) offline++;
            byState.merge(off ? "OFFLINE" : s.last().state(), 1, Integer::sum);
            battery += s.last().batteryPct();
        }
        long open = store.listIncidents(500).stream().filter(i -> "OPEN".equals(i.status())).count();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("robots", robots.size());
        summary.put("offline", offline);
        summary.put("byState", byState);
        summary.put("meanBatteryPct", robots.isEmpty() ? 0 : battery / robots.size());
        summary.put("openIncidents", open);
        return Map.of("generatedAt", now, "summary", summary, "robots", robots.stream().map(RobotState::last).toList());
    }
}
