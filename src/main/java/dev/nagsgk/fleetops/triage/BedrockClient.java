package dev.nagsgk.fleetops.triage;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.Optional;

/**
 * Amazon Bedrock via the Converse API, called in the stack's own Region with the function's IAM role
 * (no API key to store or rotate). Any failure (throttling, timeout, access) returns empty so the
 * caller falls back to plain runbook retrieval; the client's call timeout keeps the API responsive.
 */
public final class BedrockClient implements LlmClient {
    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockClient(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public Optional<String> complete(String prompt) {
        try {
            var res = client.converse(ConverseRequest.builder()
                    .modelId(modelId)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText(prompt)).build())
                    .inferenceConfig(InferenceConfiguration.builder().temperature(0.2f).maxTokens(400).build())
                    .build());
            return res.output().message().content().stream()
                    .map(ContentBlock::text).filter(t -> t != null && !t.isBlank())
                    .findFirst().map(String::strip);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "bedrock:" + modelId;
    }
}
