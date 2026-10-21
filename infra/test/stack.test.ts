import { test } from "node:test";
import * as assert from "node:assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { App } from "aws-cdk-lib";
import { Match, Template } from "aws-cdk-lib/assertions";
import { FleetOpsStack } from "../lib/fleetops-stack";

function synth(alertEmail?: string, extra: Partial<{ bedrockModelId: string }> = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "jar-"));
  const jar = path.join(dir, "fake.jar");
  fs.writeFileSync(jar, "x");
  const app = new App();
  const stack = new FleetOpsStack(app, "T", { alertEmail, jarPath: dir, env: { region: "us-east-2" }, ...extra });
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

test("triage may invoke exactly one in-Region Bedrock model; empty id disables it", () => {
  const t = synth();
  t.hasResourceProperties("AWS::Lambda::Function", {
    Handler: "dev.nagsgk.fleetops.api.QueryHandler::handleRequest",
    Environment: { Variables: Match.objectLike({ BEDROCK_MODEL_ID: "amazon.nova-lite-v1:0" }) },
  });
  t.hasResourceProperties("AWS::IAM::Policy", {
    PolicyDocument: { Statement: Match.arrayWith([Match.objectLike({
      Action: "bedrock:InvokeModel",
      Resource: { "Fn::Join": ["", ["arn:", { Ref: "AWS::Partition" }, ":bedrock:us-east-2::foundation-model/amazon.nova-lite-v1:0"]] },
    })]) },
  });
  const off = JSON.stringify(synth(undefined, { bedrockModelId: "" }).toJSON());
  assert.ok(!off.includes("bedrock:InvokeModel") && !off.includes("BEDROCK_MODEL_ID"));
});

test("keys live in Secrets Manager, never in the template or Lambda environment", () => {
  const t = synth();
  t.resourceCountIs("AWS::SecretsManager::Secret", 2);
  t.hasResourceProperties("AWS::SecretsManager::Secret", { GenerateSecretString: Match.objectLike({ PasswordLength: 48 }) });
  const fns = t.findResources("AWS::Lambda::Function");
  for (const f of Object.values(fns) as any[]) {
    const vars = f.Properties?.Environment?.Variables ?? {};
    for (const k of ["INGEST_KEY", "OPERATOR_KEY", "GEMINI_API_KEY"]) assert.ok(!(k in vars), `${k} must not be a plaintext env var`);
  }
  t.hasResourceProperties("AWS::Lambda::Function", {
    Handler: "dev.nagsgk.fleetops.api.QueryHandler::handleRequest",
    Environment: { Variables: Match.objectLike({ OPERATOR_KEY_SECRET_ARN: Match.anyValue() }) },
  });
  t.hasResourceProperties("AWS::Lambda::Function", {
    Handler: "dev.nagsgk.fleetops.api.IngestHandler::handleRequest",
    Environment: { Variables: Match.objectLike({ INGEST_KEY_SECRET_ARN: Match.anyValue() }) },
  });
});

test("each function can read only its own key", () => {
  const t = synth();
  const policies = Object.values(t.findResources("AWS::IAM::Policy")) as any[];
  const readers = (secretLogicalPrefix: string) => policies.filter((p) =>
    JSON.stringify(p.Properties.PolicyDocument).includes(secretLogicalPrefix) &&
    JSON.stringify(p.Properties.PolicyDocument).includes("secretsmanager:GetSecretValue")).map((p) => JSON.stringify(p.Properties.Roles));
  assert.strictEqual(readers("OperatorKey").length, 1);
  assert.ok(readers("OperatorKey")[0].includes("Query"));
  assert.strictEqual(readers("IngestKey").length, 1);
  assert.ok(readers("IngestKey")[0].includes("Ingest"));
});

test("browser may send the operator key header; logs kept 90 days", () => {
  const t = synth();
  t.hasResourceProperties("AWS::ApiGatewayV2::Api", {
    CorsConfiguration: Match.objectLike({ AllowHeaders: Match.arrayWith(["x-operator-key"]) }),
  });
  t.hasResourceProperties("Custom::LogRetention", { RetentionInDays: 90 });
});
