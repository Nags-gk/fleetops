package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.model.Severity;
import dev.nagsgk.fleetops.store.InMemoryFleetStore;
import dev.nagsgk.fleetops.triage.LlmClient;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TriageTest {
    final RunbookIndex idx = RunbookIndex.fromClasspath("/runbooks/drive-units.md");

    @Test
    void retrievalFindsTheMatchingRunbookSection() {
        assertThat(idx.size()).isGreaterThanOrEqualTo(10);
        assertThat(idx.search("DRIVE_STUCK commanded to move but stationary", 1).get(0).section().title()).isEqualTo("DRIVE_STUCK");
        assertThat(idx.search("dead letter queue messages failed processing", 1).get(0).section().title()).isEqualTo("INGEST_DLQ_NOT_EMPTY");
        assertThat(idx.search("lidar scanner fault", 1).get(0).section().title()).isEqualTo("ERROR_E101");
    }

    Incident inc() {
        return new Incident("i1", "DU-1", "BATTERY_CRITICAL", Severity.SEV2, "OPEN", "battery 5% while MOVING", 1, 1, 3, null);
    }

    @Test
    void llmGetsGroundedPromptAndItsAnswerIsUsed() {
        var seen = new AtomicReference<String>();
        LlmClient fake = new LlmClient() {
            public Optional<String> complete(String p) {
                seen.set(p);
                return Optional.of("Send to charger now (BATTERY_CRITICAL).");
            }

            public String name() {
                return "fake";
            }
        };
        var t = new TriageService(new InMemoryFleetStore(), idx, fake).triage(inc());
        assertThat(t.source()).isEqualTo("fake");
        assertThat(seen.get()).contains("## BATTERY_CRITICAL").contains("Only recommend actions that appear in the runbook");
    }

    static LlmClient answering(String answer) {
        return new LlmClient() {
            public Optional<String> complete(String p) {
                return Optional.of(answer);
            }

            public String name() {
                return "fake";
            }
        };
    }

    @Test
    void rejectsAnUngroundedAnswerThatCitesNoRetrievedSection() {
        var t = new TriageService(new InMemoryFleetStore(), idx, answering("Reboot the robot and update its firmware.")).triage(inc());
        assertThat(t.source()).startsWith("runbook-retrieval").contains("rejected");
        assertThat(t.text()).contains("nearest charger").doesNotContain("firmware");
    }

    @Test
    void citationMatchIsCaseAndSpaceInsensitive() {
        assertThat(TriageService.citesRetrievedSection("see Battery Critical section", java.util.List.of("BATTERY_CRITICAL"))).isTrue();
        assertThat(TriageService.citesRetrievedSection("see DRIVE_STUCK", java.util.List.of("BATTERY_CRITICAL"))).isFalse();
    }

    @Test
    void fallsBackToRunbookWhenLlmUnavailable() {
        LlmClient down = new LlmClient() {
            public Optional<String> complete(String p) {
                return Optional.empty();
            }

            public String name() {
                return "down";
            }
        };
        var t = new TriageService(new InMemoryFleetStore(), idx, down).triage(inc());
        assertThat(t.source()).isEqualTo("runbook-retrieval");
        assertThat(t.text()).contains("nearest charger");
    }
}
