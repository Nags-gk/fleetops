package dev.nagsgk.fleetops.queue;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.util.Json;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * SQS FIFO queue. MessageGroupId = robotId keeps each robot's heartbeats in order (the detector is
 * sequential per robot) while different robots are processed in parallel; the deduplication id
 * robotId-seq drops client retries within SQS's 5-minute window.
 */
public final class SqsTelemetryQueue implements TelemetryQueue {
    private final SqsClient sqs;
    private final String url;

    public SqsTelemetryQueue(SqsClient sqs, String url) {
        this.sqs = sqs;
        this.url = url;
    }

    @Override
    public List<Heartbeat> send(List<Heartbeat> batch) {
        var failed = new ArrayList<Heartbeat>();
        for (int i = 0; i < batch.size(); i += 10) {
            var chunk = batch.subList(i, Math.min(i + 10, batch.size()));
            var entries = new ArrayList<SendMessageBatchRequestEntry>();
            for (int k = 0; k < chunk.size(); k++) {
                var hb = chunk.get(k);
                entries.add(SendMessageBatchRequestEntry.builder().id(Integer.toString(k))
                        .messageBody(Json.write(hb))
                        .messageGroupId(hb.robotId())
                        .messageDeduplicationId(hb.robotId() + "-" + hb.seq())
                        .build());
            }
            var r = sqs.sendMessageBatch(SendMessageBatchRequest.builder().queueUrl(url).entries(entries).build());
            r.failed().forEach(f -> failed.add(chunk.get(Integer.parseInt(f.id()))));
        }
        return failed;
    }
}
