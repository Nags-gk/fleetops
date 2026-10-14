package dev.nagsgk.fleetops.sim;

import dev.nagsgk.fleetops.alert.Alerter;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.store.InMemoryFleetStore;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import dev.nagsgk.fleetops.util.Json;
import dev.nagsgk.fleetops.util.Metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Synthetic drive-unit fleet with injected, labelled faults.
 *
 * <pre>
 *   evaluate  [robots] [minutes] [seed]   run in-process; score the detector against ground truth
 *   load      URL KEY [robots] [minutes]  stream heartbeats to a deployed /telemetry endpoint
 * </pre>
 */
public final class FleetSimulator {

    public enum Fault { OVERHEAT, BEARING_WEAR, STUCK, BATTERY_DRAIN, LIDAR_FAULT }

    static final Map<Fault, Set<String>> EXPECTED = Map.of(
            Fault.OVERHEAT, Set.of("MOTOR_OVERTEMP", "MOTOR_TEMP_DRIFT"),
            Fault.BEARING_WEAR, Set.of("MOTOR_TEMP_DRIFT", "WHEEL_CURRENT_DRIFT"),
            Fault.STUCK, Set.of("DRIVE_STUCK"),
            Fault.BATTERY_DRAIN, Set.of("BATTERY_CRITICAL"),
            Fault.LIDAR_FAULT, Set.of("ERROR_E101"));

    public record Injected(String robotId, Fault fault, long startMs, long endMs) {}

    static final class Robot {
        final String id;
        final Random r;
        final double baseTemp, baseCurrent;
        double x, y, temp, battery, heading;
        long seq;
        String state = "MOVING";
        int stateLeft;
        Fault fault;
        long faultStart, faultEnd;

        Robot(String id, Random r) {
            this.id = id;
            this.r = r;
            baseTemp = 40 + r.nextDouble() * 15;
            baseCurrent = 2 + r.nextDouble() * 2;
            temp = baseTemp;
            battery = 40 + r.nextDouble() * 60;
            x = r.nextDouble() * 40;
            y = r.nextDouble() * 26;
            stateLeft = 5 + r.nextInt(60);
        }

        Heartbeat tick(long t) {
            boolean active = fault != null && t >= faultStart && t < faultEnd;
            double faultSec = active ? (t - faultStart) / 1000.0 : 0;
            if (--stateLeft <= 0 && !(active && (fault == Fault.STUCK))) {
                if (battery < 25) state = "CHARGING";
                else state = switch (r.nextInt(10)) { case 0, 1 -> "IDLE"; case 2 -> "LIFTING"; default -> "MOVING"; };
                stateLeft = 10 + r.nextInt(60);
                heading = r.nextDouble() * 2 * Math.PI;
            }
            if ("CHARGING".equals(state)) {
                battery = Math.min(100, battery + 0.12);
                if (battery >= 95) stateLeft = 0;
            } else {
                battery -= "MOVING".equals(state) ? 0.025 : 0.008;
            }
            double v = "MOVING".equals(state) ? 1.2 + r.nextGaussian() * 0.1 : 0;
            if ("MOVING".equals(state) && r.nextDouble() < 0.03) v = 0; // brief, normal traffic stops
            double load = "MOVING".equals(state) ? 3 : "LIFTING".equals(state) ? 5 : 0;
            double targetTemp = baseTemp + load;
            double current = baseCurrent + ("LIFTING".equals(state) ? 1.0 : 0) + r.nextGaussian() * 0.15;
            var codes = new ArrayList<String>();
            if (active) {
                switch (fault) {
                    case OVERHEAT -> targetTemp += Math.min(60, 0.8 * faultSec);
                    case BEARING_WEAR -> {
                        targetTemp += Math.min(25, 0.05 * faultSec);
                        current += Math.min(3, 0.004 * faultSec);
                    }
                    case STUCK -> {
                        state = "MOVING";
                        v = 0;
                    }
                    case BATTERY_DRAIN -> {
                        battery = Math.max(2, battery - 0.4);
                        if ("CHARGING".equals(state)) state = "MOVING";
                    }
                    case LIDAR_FAULT -> {
                        if (r.nextDouble() < 0.2) codes.add("E101");
                        v = 0;
                        state = "FAULT";
                    }
                }
            }
            temp += (targetTemp - temp) * 0.1 + r.nextGaussian() * 0.25;
            x = Math.max(0, Math.min(40, x + Math.cos(heading) * v));
            y = Math.max(0, Math.min(26, y + Math.sin(heading) * v));
            if (x <= 0 || x >= 40 || y <= 0 || y >= 26) heading += Math.PI / 2;
            return new Heartbeat(id, ++seq, t, "SIM1", state, x, y, Math.max(0, battery), temp,
                    Math.max(0, current), Math.abs(v), codes);
        }
    }

    static List<Robot> fleet(int n, int minutes, long seed, List<Injected> faults, long t0) {
        var rnd = new Random(seed);
        var robots = new ArrayList<Robot>();
        for (int i = 0; i < n; i++) robots.add(new Robot("DU-%04d".formatted(i), new Random(rnd.nextLong())));
        // one fault on ~30% of robots, starting after a 5-minute warm-up so baselines exist
        var types = Fault.values();
        for (var rb : robots) {
            if (rnd.nextDouble() < 0.3 && minutes > 10) {
                rb.fault = types[rnd.nextInt(types.length)];
                rb.faultStart = t0 + (5 + rnd.nextInt(Math.max(1, minutes - 10))) * 60_000L;
                rb.faultEnd = rb.faultStart + 4 * 60_000L;
                faults.add(new Injected(rb.id, rb.fault, rb.faultStart, rb.faultEnd));
            }
        }
        return robots;
    }

    public record Score(Map<String, Object> report, List<Incident> incidents) {}

    public static Score evaluate(int robots, int minutes, long seed) {
        long t0 = 1_760_000_000_000L;
        var faults = new ArrayList<Injected>();
        var fleet = fleet(robots, minutes, seed, faults, t0);
        var store = new InMemoryFleetStore();
        var pages = new ArrayList<Incident>();
        Alerter alerter = pages::add;
        long[] now = {t0};
        var proc = new ProcessorService(store, alerter, new AnomalyDetector(), () -> now[0]);
        var metrics = new Metrics("FleetOps");
        long anomalies = 0;
        long started = System.nanoTime();
        int beats = 0;
        for (int s = 0; s < minutes * 60; s++) {
            now[0] = t0 + s * 1000L;
            for (var rb : fleet) {
                anomalies += proc.handle(rb.tick(now[0]), metrics).size();
                beats++;
            }
        }
        double secs = (System.nanoTime() - started) / 1e9;
        var incidents = store.listIncidents(100_000);

        // score: each injected fault is detected by the first matching incident on that robot after it starts
        var latencies = new ArrayList<Long>();
        var perFault = new TreeMap<String, int[]>();
        var matched = new HashSet<String>();
        for (var f : faults) {
            var hit = incidents.stream()
                    .filter(i -> i.robotId().equals(f.robotId()) && EXPECTED.get(f.fault()).contains(i.kind())
                            && i.openedAt() >= f.startMs() && i.openedAt() <= f.endMs() + 60_000)
                    .min(Comparator.comparingLong(Incident::openedAt));
            var c = perFault.computeIfAbsent(f.fault().name(), k -> new int[2]);
            c[1]++;
            if (hit.isPresent()) {
                c[0]++;
                latencies.add(hit.get().openedAt() - f.startMs());
            }
            incidents.stream().filter(i -> i.robotId().equals(f.robotId())
                    && i.openedAt() >= f.startMs() && i.openedAt() <= f.endMs() + 120_000).forEach(i -> matched.add(i.id()));
        }
        long falsePos = incidents.stream().filter(i -> !matched.contains(i.id())).count();
        var fpByKind = new TreeMap<String, Long>();
        incidents.stream().filter(i -> !matched.contains(i.id())).forEach(i -> fpByKind.merge(i.kind(), 1L, Long::sum));
        Collections.sort(latencies);
        var recall = new LinkedHashMap<String, Object>();
        perFault.forEach((k, c) -> recall.put(k, "%d/%d".formatted(c[0], c[1])));
        int detected = perFault.values().stream().mapToInt(c -> c[0]).sum();
        var rep = new LinkedHashMap<String, Object>();
        rep.put("robots", robots);
        rep.put("simulated_minutes", minutes);
        rep.put("heartbeats", beats);
        rep.put("injected_faults", faults.size());
        rep.put("detected_faults", detected);
        rep.put("recall", faults.isEmpty() ? 1.0 : (double) detected / faults.size());
        rep.put("recall_by_fault", recall);
        rep.put("detection_latency_s_median", latencies.isEmpty() ? null : latencies.get(latencies.size() / 2) / 1000.0);
        rep.put("detection_latency_s_p95", latencies.isEmpty() ? null : latencies.get((int) Math.min(latencies.size() - 1, Math.ceil(latencies.size() * 0.95) - 1)) / 1000.0);
        rep.put("false_positive_incidents", falsePos);
        rep.put("false_positives_per_robot_hour", falsePos / (robots * minutes / 60.0));
        rep.put("false_positives_by_kind", fpByKind);
        rep.put("false_pages", incidents.stream().filter(i -> !matched.contains(i.id()) && i.severity().ordinal() <= 1).count());
        rep.put("raw_anomalies", anomalies);
        rep.put("incidents_opened", incidents.size());
        rep.put("pages_sent", pages.size());
        rep.put("alert_dedup_ratio", incidents.isEmpty() ? 0 : (double) anomalies / incidents.size());
        rep.put("processing_heartbeats_per_s_single_thread", Math.round(beats / secs));
        return new Score(rep, incidents);
    }

    /** Recorded shift for the dashboard's replay mode: frames every 10 s plus incidents with triage. */
    static Map<String, Object> replay(int robots, int minutes, long seed) {
        long t0 = 1_760_000_000_000L;
        var faults = new ArrayList<Injected>();
        var fleet = fleet(robots, minutes, seed, faults, t0);
        var store = new InMemoryFleetStore();
        var pages = new ArrayList<Incident>();
        long[] now = {t0};
        var proc = new ProcessorService(store, pages::add, new AnomalyDetector(), () -> now[0]);
        var m = new Metrics("FleetOps");
        var frames = new ArrayList<Map<String, Object>>();
        for (int s = 0; s < minutes * 60; s++) {
            now[0] = t0 + s * 1000L;
            var beats = new ArrayList<Heartbeat>();
            for (var rb : fleet) {
                var hb = rb.tick(now[0]);
                proc.handle(hb, m);
                beats.add(hb);
            }
            if (s % 10 == 0) {
                var rows = beats.stream().map(h -> List.<Object>of(h.robotId(), Math.round(h.x() * 10) / 10.0, Math.round(h.y() * 10) / 10.0,
                        h.state(), Math.round(h.batteryPct()), Math.round(h.motorTempC() * 10) / 10.0)).toList();
                frames.add(Map.of("t", now[0], "robots", rows));
            }
        }
        var triage = new TriageService(store, RunbookIndex.fromClasspath("/runbooks/drive-units.md"), null);
        var incs = store.listIncidents(10_000).stream().sorted(Comparator.comparingLong(Incident::openedAt))
                .map(i -> i.withTriage(triage.triage(i).text())).toList();
        return Map.of("site", "SIM1", "start", t0, "minutes", minutes, "frames", frames, "incidents", incs,
                "injected", faults, "pages", pages.size());
    }

    static void load(String url, String key, int robots, int minutes) throws Exception {
        var http = HttpClient.newHttpClient();
        var faults = new ArrayList<Injected>();
        long t0 = System.currentTimeMillis();
        var fleet = fleet(robots, minutes, 7, faults, t0);
        System.out.println("injected faults: " + Json.write(faults));
        for (int s = 0; s < minutes * 60; s++) {
            long now = System.currentTimeMillis();
            var batch = fleet.stream().map(rb -> rb.tick(now)).toList();
            var req = HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + "/telemetry"))
                    .header("Content-Type", "application/json").header("x-fleet-key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("heartbeats", batch)))).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 202) System.err.println(res.statusCode() + " " + res.body());
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - now)));
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "evaluate";
        if (mode.equals("replay")) {
            Files.createDirectories(Path.of("web/data"));
            Files.writeString(Path.of("web/data/replay.json"), Json.write(replay(60, 20, 11)));
            return;
        }
        if (mode.equals("load")) {
            load(args[1], args[2], args.length > 3 ? Integer.parseInt(args[3]) : 50, args.length > 4 ? Integer.parseInt(args[4]) : 15);
            return;
        }
        int robots = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int minutes = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 42;
        var score = evaluate(robots, minutes, seed);
        System.out.println(Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(score.report()));
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results/detector_eval.json"), Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(score.report()));
        // a small sample of triaged incidents for the demo dashboard (runbook retrieval mode, no LLM key)
        var triage = new TriageService(new InMemoryFleetStore(), RunbookIndex.fromClasspath("/runbooks/drive-units.md"), null);
        var sample = score.incidents().stream().filter(i -> i.severity().ordinal() <= 1).limit(12)
                .map(i -> i.withTriage(triage.triage(i).text())).toList();
        Files.writeString(Path.of("results/sample_incidents.json"), Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(sample));
    }
}
