package dev.nagsgk.fleetops.triage;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.store.FleetStore;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;

/**
 * On-call assistant: retrieval-augmented triage. Retrieves the most relevant runbook sections,
 * summarises the robot's recent telemetry, and asks an LLM for a short first-response plan that
 * must cite the runbook. With no LLM configured (or on timeout) it returns the retrieved runbook
 * steps verbatim, so triage never depends on a third-party API being up.
 */
public final class TriageService {
    private final FleetStore store;
    private final RunbookIndex index;
    private final LlmClient llm; // nullable

    public record Triage(String text, String source, List<String> runbookSections) {}

    public TriageService(FleetStore store, RunbookIndex index, LlmClient llm) {
        this.store = store;
        this.index = index;
        this.llm = llm;
    }

    static String telemetrySummary(List<Heartbeat> hbs) {
        if (hbs.isEmpty()) return "no telemetry in the last 10 minutes";
        DoubleSummaryStatistics t = hbs.stream().mapToDouble(Heartbeat::motorTempC).summaryStatistics();
        DoubleSummaryStatistics c = hbs.stream().mapToDouble(Heartbeat::wheelCurrentA).summaryStatistics();
        var last = hbs.get(hbs.size() - 1);
        var codes = hbs.stream().flatMap(h -> h.errorCodes().stream()).distinct().collect(Collectors.joining(","));
        return ("%d samples; motor temp %.1f-%.1f C (now %.1f); wheel current %.2f-%.2f A (now %.2f); "
                + "battery now %.0f%%; state now %s at (%.1f, %.1f); error codes: %s").formatted(
                hbs.size(), t.getMin(), t.getMax(), last.motorTempC(), c.getMin(), c.getMax(), last.wheelCurrentA(),
                last.batteryPct(), last.state(), last.x(), last.y(), codes.isEmpty() ? "none" : codes);
    }

    public Triage triage(Incident inc) {
        var hits = index.search(inc.kind() + " " + inc.detail(), 2);
        var since = inc.lastSeenAt() - 10 * 60_000L;
        var telem = telemetrySummary(store.telemetry(inc.robotId(), since, 600));
        var runbook = hits.stream().map(h -> "## " + h.section().title() + "\n" + h.section().text())
                .collect(Collectors.joining("\n\n"));
        var titles = hits.stream().map(h -> h.section().title()).toList();
        if (llm != null) {
            var prompt = """
                    You are assisting an on-call engineer for a warehouse robot fleet.
                    Incident: %s (%s) on robot %s, seen %d time(s). Detail: %s
                    Recent telemetry: %s

                    Relevant runbook sections:
                    %s

                    Write at most 6 short lines: the most likely cause given the telemetry, then the first
                    three actions. Only recommend actions that appear in the runbook sections above and name
                    the section you used by its exact heading (for example %s). If the telemetry contradicts
                    the runbook, say so.
                    """.formatted(inc.kind(), inc.severity(), inc.robotId(), inc.occurrences(), inc.detail(), telem, runbook,
                    titles.isEmpty() ? "MOTOR_OVERTEMP" : titles.get(0));
            var out = llm.complete(prompt);
            if (out.isPresent() && citesRetrievedSection(out.get(), titles)) return new Triage(out.get(), llm.name(), titles);
            // An answer that cites none of the retrieved sections is not grounded: do not show it.
            if (out.isPresent()) return fallback(telem, runbook, titles, "runbook-retrieval (model answer rejected: no runbook citation)");
        }
        return fallback(telem, runbook, titles, "runbook-retrieval");
    }

    /** Grounding guard: the plan must name at least one runbook section it was actually given. */
    public static boolean citesRetrievedSection(String answer, List<String> titles) {
        var a = answer.toUpperCase().replace(' ', '_');
        return titles.stream().anyMatch(t -> a.contains(t.toUpperCase()));
    }

    private static Triage fallback(String telem, String runbook, List<String> titles, String source) {
        return new Triage("Telemetry: " + telem + "\n\nFrom the runbook:\n\n" + runbook, source, titles);
    }
}
