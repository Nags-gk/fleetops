package dev.nagsgk.fleetops.triage;

import java.util.Optional;

public interface LlmClient {
    /** Empty when the model is unavailable; callers must have a non-LLM fallback. */
    Optional<String> complete(String prompt);

    String name();
}
