package dev.nagsgk.fleetops.api;

import dev.nagsgk.fleetops.alert.Alerter;
import dev.nagsgk.fleetops.alert.SnsAlerter;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.ingest.IngestService;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.queue.SqsTelemetryQueue;
import dev.nagsgk.fleetops.store.DynamoFleetStore;
import dev.nagsgk.fleetops.store.FleetStore;
import dev.nagsgk.fleetops.triage.BedrockClient;
import dev.nagsgk.fleetops.triage.GeminiClient;
import dev.nagsgk.fleetops.triage.LlmClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import java.time.Duration;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import dev.nagsgk.fleetops.auth.SecretKey;
import dev.nagsgk.fleetops.auth.Secrets;

import java.net.URI;

/** Wires adapters from environment variables (set by the CDK stack, or by tests against an emulator). */
public final class Env {
    private Env() {}

    static String req(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("missing env " + k);
        return v;
    }

    static <B extends AwsClientBuilder<B, ?>> B configure(B b) {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        return b.region(Region.of(region));
    }

    public static FleetStore store() {
        var ddb = configure(DynamoDbClient.builder()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return new DynamoFleetStore(ddb, req("TABLE_STATE"), req("TABLE_TELEMETRY"), req("TABLE_INCIDENTS"));
    }

    public static IngestService ingest() {
        var sqs = configure(SqsClient.builder()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return new IngestService(new SqsTelemetryQueue(sqs, req("QUEUE_URL")));
    }

    /**
     * Key named by {@code <prefix>_SECRET_ARN} (Secrets Manager, cached 5 minutes), or a
     * literal {@code <prefix>} value for local runs. Neither set: null, and callers fail closed.
     */
    public static SecretKey secretKey(String prefix) {
        String arn = System.getenv(prefix + "_SECRET_ARN");
        if (arn != null && !arn.isBlank()) {
            var sm = configure(SecretsManagerClient.builder()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            return new SecretKey(Secrets.fromSecretsManager(sm, arn), System::currentTimeMillis, 300_000);
        }
        String literal = System.getenv(prefix);
        return literal == null || literal.isBlank() ? null : SecretKey.fixed(literal);
    }

    public static Alerter alerter() {
        var sns = configure(SnsClient.builder()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return new SnsAlerter(sns, req("TOPIC_ARN"));
    }

    public static ProcessorService processor() {
        return new ProcessorService(store(), alerter(), new AnomalyDetector(), System::currentTimeMillis);
    }

    /** Bedrock when BEDROCK_MODEL_ID is set (preferred: IAM auth, same Region), else Gemini with a key, else none. */
    static LlmClient llm() {
        String model = System.getenv("BEDROCK_MODEL_ID");
        if (model != null && !model.isBlank()) {
            var brt = configure(BedrockRuntimeClient.builder())
                    .httpClientBuilder(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(9)))
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10)).retryStrategy(b -> b.maxAttempts(2)))
                    .build();
            return new BedrockClient(brt, model);
        }
        String key = System.getenv("GEMINI_API_KEY");
        return key == null || key.isBlank() ? null
                : new GeminiClient(key, System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.1-flash-lite"));
    }

    public static TriageService triage(FleetStore store) {
        var llm = llm();
        return new TriageService(store, RunbookIndex.fromClasspath("/runbooks/drive-units.md"), llm);
    }
}
