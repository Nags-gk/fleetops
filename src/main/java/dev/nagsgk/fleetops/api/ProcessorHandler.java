package dev.nagsgk.fleetops.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import dev.nagsgk.fleetops.process.ProcessorService;
import dev.nagsgk.fleetops.util.Metrics;

/** SQS FIFO -> processor, with partial batch failure reporting (ReportBatchItemFailures). */
public final class ProcessorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final ProcessorService svc;

    public ProcessorHandler() {
        this(Env.processor());
    }

    public ProcessorHandler(ProcessorService svc) {
        this.svc = svc;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent ev, Context ctx) {
        var metrics = new Metrics("FleetOps");
        var msgs = ev.getRecords().stream().map(r -> new ProcessorService.Message(r.getMessageId(), r.getBody())).toList();
        var failed = svc.process(msgs, metrics);
        metrics.flush("processor");
        return SQSBatchResponse.builder().withBatchItemFailures(
                failed.stream().map(id -> SQSBatchResponse.BatchItemFailure.builder().withItemIdentifier(id).build()).toList()
        ).build();
    }
}
