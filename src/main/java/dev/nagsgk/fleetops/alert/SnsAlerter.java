package dev.nagsgk.fleetops.alert;

import dev.nagsgk.fleetops.model.Incident;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

public final class SnsAlerter implements Alerter {
    private final SnsClient sns;
    private final String topicArn;

    public SnsAlerter(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    public void page(Incident i) {
        String subject = "[%s] %s on %s".formatted(i.severity(), i.kind(), i.robotId());
        String body = """
                %s
                Robot:    %s
                Incident: %s
                Detail:   %s
                Triage:   GET /incidents/%s  or  POST /incidents/%s/triage
                """.formatted(subject, i.robotId(), i.id(), i.detail(), i.id(), i.id());
        sns.publish(PublishRequest.builder().topicArn(topicArn).subject(subject.length() > 99 ? subject.substring(0, 99) : subject)
                .message(body)
                .messageAttributes(Map.of("severity", MessageAttributeValue.builder().dataType("String")
                        .stringValue(i.severity().name()).build()))
                .build());
    }
}
