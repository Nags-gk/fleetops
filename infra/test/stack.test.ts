import { test } from "node:test";
import * as assert from "node:assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { App } from "aws-cdk-lib";
import { Match, Template } from "aws-cdk-lib/assertions";
import { FleetOpsStack } from "../lib/fleetops-stack";

function synth(alertEmail?: string) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "jar-"));
  const jar = path.join(dir, "fake.jar");
  fs.writeFileSync(jar, "x");
  const app = new App();
  const stack = new FleetOpsStack(app, "T", { ingestKey: "k", alertEmail, jarPath: dir, env: { region: "us-east-1" } });
  return Template.fromStack(stack);
}

test("FIFO telemetry queue with a FIFO dead-letter queue", () => {
  const t = synth();
  t.hasResourceProperties("AWS::SQS::Queue", { FifoQueue: true, RedrivePolicy: Match.objectLike({ maxReceiveCount: 5 }) });
  t.resourceCountIs("AWS::SQS::Queue", 2);
});

test("processor reports partial batch failures", () => {
  synth().hasResourceProperties("AWS::Lambda::EventSourceMapping", { BatchSize: 10, FunctionResponseTypes: ["ReportBatchItemFailures"] });
});

test("three Java 21 Lambdas with tracing; SnapStart on API-facing ones", () => {
  const t = synth();
  const fns = t.findResources("AWS::Lambda::Function", { Properties: { Runtime: "java21" } });
  assert.strictEqual(Object.keys(fns).length, 3);
  t.hasResourceProperties("AWS::Lambda::Function", { TracingConfig: { Mode: "Active" }, SnapStart: { ApplyOn: "PublishedVersions" } });
});

test("telemetry table expires items via TTL; incidents have the sparse open-key index", () => {
  const t = synth();
  t.hasResourceProperties("AWS::DynamoDB::Table", { TimeToLiveSpecification: { AttributeName: "expiresAt", Enabled: true } });
  t.hasResourceProperties("AWS::DynamoDB::Table", { GlobalSecondaryIndexes: Match.arrayWith([Match.objectLike({ IndexName: "open-key-index" })]) });
});

test("alarms page on-call, API is throttled, budget only with an email", () => {
  const t = synth();
  t.resourceCountIs("AWS::CloudWatch::Alarm", 4);
  t.hasResourceProperties("AWS::ApiGatewayV2::Stage", { DefaultRouteSettings: { ThrottlingRateLimit: 50, ThrottlingBurstLimit: 100 } });
  t.resourceCountIs("AWS::Budgets::Budget", 0);
  synth("oncall@example.com").resourceCountIs("AWS::Budgets::Budget", 1);
});
