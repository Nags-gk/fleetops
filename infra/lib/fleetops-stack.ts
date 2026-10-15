import * as path from "path";
import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as dynamodb from "aws-cdk-lib/aws-dynamodb";
import * as sqs from "aws-cdk-lib/aws-sqs";
import * as sns from "aws-cdk-lib/aws-sns";
import * as subs from "aws-cdk-lib/aws-sns-subscriptions";
import * as lambda from "aws-cdk-lib/aws-lambda";
import { SqsEventSource } from "aws-cdk-lib/aws-lambda-event-sources";
import * as apigw from "aws-cdk-lib/aws-apigatewayv2";
import { HttpLambdaIntegration } from "aws-cdk-lib/aws-apigatewayv2-integrations";
import * as cw from "aws-cdk-lib/aws-cloudwatch";
import * as cwActions from "aws-cdk-lib/aws-cloudwatch-actions";
import * as logs from "aws-cdk-lib/aws-logs";
import * as budgets from "aws-cdk-lib/aws-budgets";
import * as iam from "aws-cdk-lib/aws-iam";

export interface FleetOpsProps extends StackProps {
  /** shared secret robots send in x-fleet-key */
  ingestKey: string;
  /** on-call email for pages, alarms and the budget alert (optional) */
  alertEmail?: string;
  /**
   * Bedrock model for LLM triage, invoked in this stack's Region with the function's IAM role.
   * Must be an in-Region on-demand model (not a cross-Region "us."/"global." inference profile).
   * Set to "" to disable; triage then returns retrieved runbook steps.
   */
  bedrockModelId?: string;
  /** optional Gemini API key, used only when no Bedrock model is set */
  geminiKey?: string;
  /** path to the shaded Lambda jar */
  jarPath?: string;
  /** browser origins allowed to call the read API (the GitHub Pages dashboard) */
  allowedOrigins?: string[];
}

export class FleetOpsStack extends Stack {
  constructor(scope: Construct, id: string, props: FleetOpsProps) {
    super(scope, id, props);

    // ---------- storage ----------
    const state = new dynamodb.Table(this, "RobotState", {
      partitionKey: { name: "robotId", type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
    });
    const telemetry = new dynamodb.Table(this, "Telemetry", {
      partitionKey: { name: "robotId", type: dynamodb.AttributeType.STRING },
      sortKey: { name: "sk", type: dynamodb.AttributeType.STRING },
      timeToLiveAttribute: "expiresAt",
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
    });
    const incidents = new dynamodb.Table(this, "Incidents", {
      partitionKey: { name: "id", type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
    });
    incidents.addGlobalSecondaryIndex({
      indexName: "open-key-index",
      partitionKey: { name: "openKey", type: dynamodb.AttributeType.STRING },
    });

    // ---------- messaging ----------
    const dlq = new sqs.Queue(this, "TelemetryDlq", {
      fifo: true,
      retentionPeriod: Duration.days(14),
    });
    const queue = new sqs.Queue(this, "TelemetryQueue", {
      fifo: true,
      visibilityTimeout: Duration.seconds(120),
      deadLetterQueue: { queue: dlq, maxReceiveCount: 5 },
    });
    const oncall = new sns.Topic(this, "OnCall", { displayName: "FleetOps on-call" });
    if (props.alertEmail) oncall.addSubscription(new subs.EmailSubscription(props.alertEmail));

    // ---------- compute ----------
    const code = lambda.Code.fromAsset(props.jarPath ?? path.join(__dirname, "..", "..", "..", "target", "fleetops-0.1.0.jar"));
    const env = {
      TABLE_STATE: state.tableName,
      TABLE_TELEMETRY: telemetry.tableName,
      TABLE_INCIDENTS: incidents.tableName,
      QUEUE_URL: queue.queueUrl,
      TOPIC_ARN: oncall.topicArn,
      JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1",
    };
    const common = {
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      code,
      memorySize: 1024,
      tracing: lambda.Tracing.ACTIVE,
      logRetention: logs.RetentionDays.TWO_WEEKS,
    };
    const ingest = new lambda.Function(this, "Ingest", {
      ...common,
      handler: "dev.nagsgk.fleetops.api.IngestHandler::handleRequest",
      timeout: Duration.seconds(10),
      environment: { ...env, INGEST_KEY: props.ingestKey },
      snapStart: lambda.SnapStartConf.ON_PUBLISHED_VERSIONS,
    });
    const processor = new lambda.Function(this, "Processor", {
      ...common,
      handler: "dev.nagsgk.fleetops.api.ProcessorHandler::handleRequest",
      timeout: Duration.seconds(60),
      environment: env,
    });
    const bedrockModelId = props.bedrockModelId ?? "amazon.nova-lite-v1:0";
    const query = new lambda.Function(this, "Query", {
      ...common,
      handler: "dev.nagsgk.fleetops.api.QueryHandler::handleRequest",
      timeout: Duration.seconds(20),
      environment: {
        ...env,
        ...(bedrockModelId ? { BEDROCK_MODEL_ID: bedrockModelId } : {}),
        ...(props.geminiKey ? { GEMINI_API_KEY: props.geminiKey } : {}),
      },
      snapStart: lambda.SnapStartConf.ON_PUBLISHED_VERSIONS,
    });
    processor.addEventSource(new SqsEventSource(queue, { batchSize: 10, reportBatchItemFailures: true }));

    // least privilege
    queue.grantSendMessages(ingest);
    state.grantReadWriteData(processor);
    telemetry.grantReadWriteData(processor);
    incidents.grantReadWriteData(processor);
    oncall.grantPublish(processor);
    state.grantReadData(query);
    telemetry.grantReadData(query);
    incidents.grantReadWriteData(query);
    if (bedrockModelId) {
      // One model, this Region only (Converse is authorised by bedrock:InvokeModel).
      query.addToRolePolicy(new iam.PolicyStatement({
        actions: ["bedrock:InvokeModel"],
        resources: [`arn:${this.partition}:bedrock:${this.region}::foundation-model/${bedrockModelId}`],
      }));
    }

    const ingestLive = new lambda.Alias(this, "IngestLive", { aliasName: "live", version: ingest.currentVersion });
    const queryLive = new lambda.Alias(this, "QueryLive", { aliasName: "live", version: query.currentVersion });

    // ---------- API ----------
    const api = new apigw.HttpApi(this, "Api", {
      corsPreflight: {
        allowOrigins: props.allowedOrigins ?? ["https://nags-gk.github.io", "http://localhost:8000"],
        allowMethods: [apigw.CorsHttpMethod.GET, apigw.CorsHttpMethod.POST],
        allowHeaders: ["content-type", "x-fleet-key"],
      },
    });
    const ingestInt = new HttpLambdaIntegration("IngestInt", ingestLive);
    const queryInt = new HttpLambdaIntegration("QueryInt", queryLive);
    api.addRoutes({ path: "/telemetry", methods: [apigw.HttpMethod.POST], integration: ingestInt });
    for (const [p, m] of [
      ["/fleet", apigw.HttpMethod.GET],
      ["/robots/{id}/telemetry", apigw.HttpMethod.GET],
      ["/incidents", apigw.HttpMethod.GET],
      ["/incidents/{id}/triage", apigw.HttpMethod.POST],
      ["/incidents/{id}/resolve", apigw.HttpMethod.POST],
    ] as const) {
      api.addRoutes({ path: p, methods: [m], integration: queryInt });
    }
    // cost and abuse guard on the default stage
    const stage = api.defaultStage!.node.defaultChild as apigw.CfnStage;
    stage.defaultRouteSettings = { throttlingRateLimit: 50, throttlingBurstLimit: 100 };

    // ---------- observability ----------
    const alarmAction = new cwActions.SnsAction(oncall);
    const metric = (name: string, fn: string, stat = "Sum") =>
      new cw.Metric({ namespace: "FleetOps", metricName: name, dimensionsMap: { Function: fn }, statistic: stat, period: Duration.minutes(1) });

    const alarms = [
      new cw.Alarm(this, "DlqNotEmpty", {
        alarmName: "fleetops-INGEST_DLQ_NOT_EMPTY",
        metric: dlq.metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(1) }),
        threshold: 1, evaluationPeriods: 1, comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: "Telemetry messages failed processing 5 times. Runbook: INGEST_DLQ_NOT_EMPTY",
      }),
      new cw.Alarm(this, "ProcessorErrors", {
        alarmName: "fleetops-PROCESSOR_ERRORS",
        metric: processor.metricErrors({ period: Duration.minutes(5) }),
        threshold: 1, evaluationPeriods: 1, comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cw.TreatMissingData.NOT_BREACHING,
        alarmDescription: "Processor Lambda errors. Runbook: PROCESSOR_ERRORS",
      }),
      new cw.Alarm(this, "IngestLag", {
        alarmName: "fleetops-INGEST_LAG",
        metric: queue.metricApproximateAgeOfOldestMessage({ period: Duration.minutes(1) }),
        threshold: 60, evaluationPeriods: 3, comparisonOperator: cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cw.TreatMissingData.NOT_BREACHING,
        alarmDescription: "Heartbeats waiting over 60 s for 3 minutes. Runbook: INGEST_LAG",
      }),
      new cw.Alarm(this, "Api5xx", {
        alarmName: "fleetops-API_5XX",
        metric: api.metricServerError({ period: Duration.minutes(5) }),
        threshold: 5, evaluationPeriods: 1, comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cw.TreatMissingData.NOT_BREACHING,
      }),
    ];
    alarms.forEach((a) => a.addAlarmAction(alarmAction));

    const dash = new cw.Dashboard(this, "Dashboard", { dashboardName: "FleetOps" });
    dash.addWidgets(
      new cw.GraphWidget({ title: "Heartbeats processed / min", left: [metric("HeartbeatsProcessed", "processor")], width: 8 }),
      new cw.GraphWidget({ title: "Ingest lag p95 (ms)", left: [metric("IngestLagMs", "processor", "p95")], width: 8 }),
      new cw.GraphWidget({ title: "Anomalies, incidents, pages", left: [metric("Anomalies", "processor"), metric("IncidentsOpened", "processor"), metric("Pages", "processor")], width: 8 }),
      new cw.GraphWidget({ title: "Queue depth and age", left: [queue.metricApproximateNumberOfMessagesVisible()], right: [queue.metricApproximateAgeOfOldestMessage()], width: 8 }),
      new cw.GraphWidget({ title: "Lambda p95 duration (ms)", left: [ingest.metricDuration({ statistic: "p95" }), processor.metricDuration({ statistic: "p95" }), query.metricDuration({ statistic: "p95" })], width: 8 }),
      new cw.AlarmStatusWidget({ title: "Alarms", alarms, width: 8 }),
    );

    if (props.alertEmail) {
      new budgets.CfnBudget(this, "Budget", {
        budget: { budgetType: "COST", timeUnit: "MONTHLY", budgetLimit: { amount: 5, unit: "USD" } },
        notificationsWithSubscribers: [{
          notification: { notificationType: "ACTUAL", comparisonOperator: "GREATER_THAN", threshold: 80, thresholdType: "PERCENTAGE" },
          subscribers: [{ subscriptionType: "EMAIL", address: props.alertEmail }],
        }],
      });
    }

    new CfnOutput(this, "ApiUrl", { value: api.apiEndpoint });
    new CfnOutput(this, "DashboardUrl", { value: `https://${this.region}.console.aws.amazon.com/cloudwatch/home?region=${this.region}#dashboards:name=FleetOps` });
    new CfnOutput(this, "DeadLetterQueueUrl", { value: dlq.queueUrl });
  }
}
