package dev.nagsgk.fleetops.api;

import dev.nagsgk.fleetops.alert.Alerter;
import dev.nagsgk.fleetops.alert.SnsAlerter;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.ingest.IngestService;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.queue.SqsTelemetryQueue;
import dev.nagsgk.fleetops.store.DynamoFleetStore;
import dev.nagsgk.fleetops.store.FleetStore;
import dev.nagsgk.fleetops.triage.GeminiClient;
import dev.nagsgk.fleetops.triage.RunbookIndex;
import dev.nagsgk.fleetops.triage.TriageService;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

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

    public static Alerter alerter() {
        var sns = configure(SnsClient.builder()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return new SnsAlerter(sns, req("TOPIC_ARN"));
    }

    public static ProcessorService processor() {
        return new ProcessorService(store(), alerter(), new AnomalyDetector(), System::currentTimeMillis);
    }

    public static TriageService triage(FleetStore store) {
        String key = System.getenv("GEMINI_API_KEY");
        var llm = key == null || key.isBlank() ? null
                : new GeminiClient(key, System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash"));
        return new TriageService(store, RunbookIndex.fromClasspath("/runbooks/drive-units.md"), llm);
    }
}
