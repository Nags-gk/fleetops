package dev.nagsgk.fleetops.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import dev.nagsgk.fleetops.model.RobotState;
import dev.nagsgk.fleetops.store.FleetStore;
import dev.nagsgk.fleetops.triage.TriageService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Read API for the operations dashboard and on-call:
 * <pre>
 *   GET  /fleet                      summary + every robot's latest state
 *   GET  /robots/{id}/telemetry      last 10 minutes (or ?since=epochMillis)
 *   GET  /incidents                  newest first
 *   POST /incidents/{id}/triage      RAG triage (cached on the incident)
 *   POST /incidents/{id}/resolve
 * </pre>
 */
public final class QueryHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    static final long OFFLINE_AFTER_MS = 30_000;

    private final FleetStore store;
    private final TriageService triage;
    private final LongSupplier clock;

    public QueryHandler() {
        this(Env.store(), null, System::currentTimeMillis);
    }

    public QueryHandler(FleetStore store, TriageService triage, LongSupplier clock) {
        this.store = store;
        this.triage = triage == null ? Env.triage(store) : triage;
        this.clock = clock;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent ev, Context ctx) {
        String route = ev.getRouteKey() == null ? "" : ev.getRouteKey();
        var p = ev.getPathParameters() == null ? Map.<String, String>of() : ev.getPathParameters();
        var q = ev.getQueryStringParameters() == null ? Map.<String, String>of() : ev.getQueryStringParameters();
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
