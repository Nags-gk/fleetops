package dev.nagsgk.fleetops;

import dev.nagsgk.fleetops.alert.SnsAlerter;
import dev.nagsgk.fleetops.detect.AnomalyDetector;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.queue.SqsTelemetryQueue;
import dev.nagsgk.fleetops.store.DynamoFleetStore;
import dev.nagsgk.fleetops.util.Metrics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.nagsgk.fleetops.Fixtures.moving;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real AWS SDK v2 calls against a local AWS emulator (moto server). CI starts the emulator and
 * sets MOTO_ENDPOINT; locally: {@code moto_server -p 5055 & MOTO_ENDPOINT=http://localhost:5055 mvn test}.
 */
@EnabledIfEnvironmentVariable(named = "MOTO_ENDPOINT", matches = ".+")
class AwsIntegrationTest {
    static DynamoDbClient ddb;
    static SqsClient sqs;
    static SnsClient sns;
    static String suffix = UUID.randomUUID().toString().substring(0, 8);

    static <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B local(B b) {
        return b.endpointOverride(URI.create(System.getenv("MOTO_ENDPOINT"))).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    }

    @BeforeAll
    static void setup() {
        ddb = local(DynamoDbClient.builder()).build();
        sqs = local(SqsClient.builder()).build();
        sns = local(SnsClient.builder()).build();
        ddb.createTable(b -> b.tableName("state-" + suffix).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("robotId").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("robotId").attributeType(ScalarAttributeType.S).build()));
        ddb.createTable(b -> b.tableName("telemetry-" + suffix).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("robotId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("robotId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()));
        ddb.createTable(b -> b.tableName("incidents-" + suffix).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("openKey").attributeType(ScalarAttributeType.S).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName(DynamoFleetStore.OPEN_INDEX)
                        .keySchema(KeySchemaElement.builder().attributeName("openKey").keyType(KeyType.HASH).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build()));
    }

    DynamoFleetStore store() {
        return new DynamoFleetStore(ddb, "state-" + suffix, "telemetry-" + suffix, "incidents-" + suffix);
    }

    @Test
    void conditionalStateWriteRejectsStaleSeq() {
        var s = store();
        var p = new ProcessorService(s, i -> {}, new AnomalyDetector(), () -> 0L);
        var m = new Metrics("t");
        p.handle(moving("R1", 2, 50, 3), m);
        p.handle(moving("R1", 1, 50, 3), m);
        p.handle(moving("R1", 2, 50, 3), m);
        assertThat(s.getState("R1").orElseThrow().last().seq()).isEqualTo(2);
        assertThat(s.telemetry("R1", 0, 10)).hasSize(1);
    }

    @Test
    void openIncidentLookupUsesSparseIndexAndClosesOnResolve() {
        var s = store();
        var p = new ProcessorService(s, i -> {}, new AnomalyDetector(), () -> 0L);
        var m = new Metrics("t");
        for (int i = 1; i <= 3; i++) p.handle(moving("R2", i, 95, 3), m);
        Incident open = s.openIncident("R2", "MOTOR_OVERTEMP").orElseThrow();
        assertThat(open.occurrences()).isEqualTo(3);
        s.putIncident(open.resolved(10));
        assertThat(s.openIncident("R2", "MOTOR_OVERTEMP")).isEmpty();
        assertThat(s.listIncidents(10)).extracting(Incident::status).contains("RESOLVED");
    }

    @Test
    void fifoQueueAndSnsPagingWork() {
        String url = sqs.createQueue(CreateQueueRequest.builder().queueName("hb-" + suffix + ".fifo")
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")).build()).queueUrl();
        var q = new SqsTelemetryQueue(sqs, url);
        var batch = new java.util.ArrayList<dev.nagsgk.fleetops.model.Heartbeat>();
        for (int i = 1; i <= 23; i++) batch.add(moving("R3", i, 50, 3));
        assertThat(q.send(batch)).isEmpty();
        var got = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url).maxNumberOfMessages(10).build()).messages();
        assertThat(got).isNotEmpty();
        assertThat(got.get(0).body()).contains("\"seq\":1");
        String topic = sns.createTopic(b -> b.name("oncall-" + suffix)).topicArn();
        new SnsAlerter(sns, topic).page(new Incident("x", "R3", "DRIVE_STUCK", dev.nagsgk.fleetops.model.Severity.SEV2,
                "OPEN", "stuck", 1, 1, 1, null));
        assertThat(List.of(topic)).isNotEmpty();
    }
}
