#!/usr/bin/env node
import { App } from "aws-cdk-lib";
import { FleetOpsStack } from "../lib/fleetops-stack";

const app = new App();
const ingestKey = app.node.tryGetContext("ingestKey") ?? process.env.INGEST_KEY;
if (!ingestKey) throw new Error("pass -c ingestKey=<secret> (deploy.sh generates one)");
new FleetOpsStack(app, "FleetOps", {
  ingestKey,
  alertEmail: app.node.tryGetContext("alertEmail") ?? process.env.ALERT_EMAIL,
  bedrockModelId: app.node.tryGetContext("bedrockModelId") ?? process.env.BEDROCK_MODEL_ID,
  geminiKey: app.node.tryGetContext("geminiKey") ?? process.env.GEMINI_API_KEY,
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? "us-east-2" },
});
