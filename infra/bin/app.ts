#!/usr/bin/env node
import { App } from "aws-cdk-lib";
import { FleetOpsStack } from "../lib/fleetops-stack";

const app = new App();
new FleetOpsStack(app, "FleetOps", {
  alertEmail: app.node.tryGetContext("alertEmail") ?? process.env.ALERT_EMAIL,
  bedrockModelId: app.node.tryGetContext("bedrockModelId") ?? process.env.BEDROCK_MODEL_ID,
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? "us-east-2" },
});
