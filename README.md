# FleetOps

**Serverless telemetry, anomaly detection and on-call triage for a warehouse robot fleet on AWS.**
Java 21 Lambdas, SQS FIFO, DynamoDB, SNS and CloudWatch are defined as one AWS CDK (TypeScript)
stack. The service includes a streaming CUSUM fault detector and a retrieval-augmented LLM
assistant on Amazon Bedrock that drafts first-response steps from the runbook.

**Dashboard: https://nags-gk.github.io/fleetops/** shows a replay of a simulated shift. Add `?api=<your API URL>` to watch a live deployment.

[![CI / CD](https://github.com/Nags-gk/fleetops/actions/workflows/ci.yml/badge.svg)](https://github.com/Nags-gk/fleetops/actions/workflows/ci.yml)

## Architecture

```mermaid
flowchart LR
  R[Drive units<br/>1 Hz heartbeats] -->|POST /telemetry<br/>x-fleet-key| G[API Gateway HTTP API<br/>throttled]
  G --> I[Ingest λ<br/>validate, batch]
  I -->|group = robotId<br/>dedup = robotId-seq| Q[(SQS FIFO)]
  Q -->|batches of 10<br/>partial failures| P[Processor λ<br/>CUSUM detector]
  Q -.->|5 failures| D[(DLQ)]
  P -->|conditional on seq| S[(DynamoDB<br/>RobotState)]
  P --> T[(DynamoDB<br/>Telemetry, TTL 7 d)]
  P -->|open / fold| N[(DynamoDB<br/>Incidents, sparse GSI)]
  P -->|SEV1/SEV2 only| SNS[SNS on-call]
  G --> QY[Query λ]
  QY --> S & T & N
  QY -->|RAG: TF-IDF runbook retrieval| LLM[Amazon Bedrock<br/>Nova Lite, same Region]
  CW[CloudWatch EMF metrics,<br/>alarms, dashboard, X-Ray] -.-> SNS
```

| Concern | How it is handled |
|---|---|
| **Ordering** | SQS FIFO message group per robot. The detector is sequential per robot while robots are processed in parallel ([ADR 0001](docs/adr/0001-sqs-fifo-over-kinesis.md)). |
| **Idempotency** | SQS de-duplication id `robotId-seq`, plus a DynamoDB conditional write `seq < :seq`. Duplicates and late samples are dropped and counted. |
| **Fault tolerance** | Partial batch responses retry only failed messages. Poison payloads are dropped and counted. Five failures send a message to a DLQ, which raises an alarm. |
| **Fault detection** | Hard limits (over-temperature, critical battery, error codes), a stuck-drive timer, and CUSUM drift detection on each robot's own EWMA baseline. Only settled MOVING samples are used, and adaptation pauses during a suspected drift. |
| **Alert hygiene** | One incident per (robot, kind) while open; repeats increment `occurrences` instead of paging. SEV3 opens a ticket only. |
| **On-call** | `POST /incidents/{id}/triage` retrieves runbook sections (TF-IDF) and summarises the last 10 minutes of telemetry. It asks Amazon Bedrock (Nova Lite, Converse API) for a plan under a strict "only recommend runbook actions, cite the section" prompt. A grounding guard rejects any answer that cites none of the retrieved sections. The function calls Bedrock with its own IAM role, scoped to one model in one Region, so there is no API key. Triage falls back to the retrieved steps on a timeout, throttling or a rejected answer. |
| **Observability** | CloudWatch Embedded Metric Format (no PutMetricData calls on the hot path), four alarms wired to the on-call topic, a dashboard, and X-Ray tracing. |
| **Cost and security** | Free-tier-sized design, API throttling (50 rps, burst 100), a $5 AWS Budget alert, least-privilege IAM grants per function, and a constant-time API-key check. |
| **Cold starts** | SnapStart on the API-facing functions behind a `live` alias, with no framework ([ADR 0003](docs/adr/0003-lambda-java-snapstart.md)). |

## Detector results

From `results/detector_eval.json`, produced by `mvn exec:java -Dexec.args="evaluate 200 120 7"`:
200 simulated robots over 2 hours (1,440,000 heartbeats), with 61 labelled faults injected after a 5-minute warm-up.

| Fault | Detected |
|---|---:|
| Battery drain | 9/9 |
| Bearing wear (slow temp + current drift) | 9/9 |
| Lidar fault (E101) | 19/19 |
| Motor overheat | 10/10 |
| Stuck drive | 14/14 |

* **Recall:** 100% (61/61). Median time to detect is **20 s** and p95 is 114 s.
* **False alarms:** **zero false pages**. There were 4 false SEV3 tickets, or 0.01 per robot-hour.
* **Alert de-duplication:** 8,025 raw anomalies were folded into 82 incidents and 52 pages, about 98 anomalies per incident.
* **Throughput:** about **150k heartbeats/s** on one core (detector + in-memory store, excluding I/O).

How the detector got there, as measured:

1. A first version with plain z-scores produced 1.0 false incidents per robot-hour, triggered by load changes.
2. Switching to CUSUM and judging only settled MOVING samples did not help at first.
3. A selection-bias bug was the real cause: the baseline learned only from in-control samples. Fixing it cut false incidents to 0.04 per robot-hour.
4. Running-average warm-up brought it to 0.01.

## Tests

```bash
mvn verify                                    # 27 unit tests: detector, processor, ingest, API, write auth, RAG + grounding guard, regression
moto_server -p 5055 &                         # local AWS emulator
MOTO_ENDPOINT=http://localhost:5055 mvn verify  # + 3 integration tests: real AWS SDK v2 calls
cd infra && npm ci && npm test                # 9 CDK assertion tests on the synthesized CloudFormation
```

CI runs all of the above on every push, re-scores the detector, synthesizes the stack and publishes the dashboard.

## Live deployment

Deployed with `./deploy.sh` to us-east-2 (AWS free plan). The API is
`https://i5nmbvd3u4.execute-api.us-east-2.amazonaws.com`; watch it at
[nags-gk.github.io/fleetops/?api=…](https://nags-gk.github.io/fleetops/?api=https://i5nmbvd3u4.execute-api.us-east-2.amazonaws.com).

A 12-minute run of `./load.sh 30 12` (30 simulated robots, 9 injected faults) against the live stack:
**9/9 faults detected with zero false incidents**. The dead-letter queue stayed empty and all four alarms stayed OK.

Live triage on those incidents (Bedrock Nova Lite, us-east-2) answered in 0.9–1.6 s. On MOTOR_OVERTEMP, DRIVE_STUCK and ERROR_E101,
all 9 recommended actions were steps that appear in the cited runbook section.

## Deploy to your AWS account

```bash
./deploy.sh you@example.com            # builds, bootstraps CDK, deploys, prints API + dashboard URLs
./load.sh 50 15                        # 50 simulated robots with injected faults for 15 minutes
(cd infra && npx cdk destroy FleetOps)   # tear down
```

You can also run the **Deploy to AWS** GitHub Actions workflow, using an OIDC role or access-key secrets.
Confirm the SNS email subscription to receive pages. If you signed in with `aws login`, export the
session first (`eval "$(aws configure export-credentials --format env)"`), since the pinned CDK CLI
does not read that credential type directly. Triage uses Bedrock `amazon.nova-lite-v1:0` by default
(`-c bedrockModelId=...` to change it, `""` to turn it off). Pick an on-demand model offered in your Region, not a
cross-Region `us.`/`global.` inference profile. `deploy.sh` copies the generated ingest key to `.ingest-key` (git-ignored, mode 600)
for the simulator and prints the command that reveals the operator key.

## API

| Method | Path | |
|---|---|---|
| POST | `/telemetry` | `{"heartbeats":[...]}` with header `x-fleet-key`; up to 500 per request. Returns 202 (accepted), 422 (none valid), 401 or 503. |
| GET | `/fleet` | fleet summary and every robot's latest heartbeat |
| GET | `/robots/{id}/telemetry?since=` | time series (default last 10 minutes) |
| GET | `/incidents?limit=` | newest first |
| POST | `/incidents/{id}/triage` | RAG triage, stored on the incident. **Requires `x-operator-key`.** |
| POST | `/incidents/{id}/resolve` | closes it and removes it from the open index. **Requires `x-operator-key`.** |

## Security review and fixes

I ran my own threat-modeling tool, [ThreatLens](https://github.com/Nags-gk/threatlens), over this stack as built
([before](https://github.com/Nags-gk/threatlens/blob/main/results/reviews/fleetops_as_built.md)). It found three critical
issues; this release fixes or reduces each one and adds tests that keep them fixed.

| Finding | Before | Now |
|---|---|---|
| Unauthenticated writes (TL-E04, TL-S01) | Anyone with the API URL could `POST /incidents/{id}/resolve` and silence on-call, or call `/triage` repeatedly to spend Bedrock budget | Both routes need `x-operator-key`, checked in constant time; writes **fail closed** if the key cannot be loaded. Reads stay public for the dashboard. `WriteAuthTest` covers wrong, empty and missing keys, and confirms a rejected write changes nothing |
| Keys in Lambda environment variables (TL-I04) | The ingest key (and an optional Gemini key) were plain environment variables, visible to anyone with `lambda:GetFunctionConfiguration` and copied into the CloudFormation template | Both keys are generated by Secrets Manager; each function can read only its own. Keys are fetched on first request, not at init, so SnapStart snapshots never contain them, and they are cached for 5 minutes so a rotation takes effect without a redeploy |
| No audit trail for writes (TL-R01) | No record of who closed an incident | Every write attempt logs a structured `fleetops.write` record (route, incident, source IP, user agent, request ID, allowed/denied); the key itself is never logged |
| 14-day log retention (TL-L03) | Too short to investigate slow abuse | 90 days |
| One key shared by every robot (TL-S03) | Still true | **Open.** The key now lives in Secrets Manager and can be rotated, but per-device identity (AWS IoT Core with X.509 certificates) is the real fix |

The operator key is a single shared secret, so the audit log records *where* a write came from, not *who* made it. The next step is
per-user sign-in (Cognito with an API Gateway JWT authorizer), which would put an identity on every audit record.

## Layout

```
src/main/java/dev/nagsgk/fleetops/
  model/      Heartbeat, RobotState (+ detector stats), Incident, Anomaly
  detect/     AnomalyDetector (limits, CUSUM drift, stuck timer)
  ingest/ process/ triage/   application services (ports & adapters)
  store/ queue/ alert/       DynamoDB, SQS FIFO, SNS adapters + in-memory fakes
  api/        Lambda handlers (ingest, processor, query) and env wiring
  sim/        fleet simulator: fault injection, scoring, load generator, dashboard replay
src/main/resources/runbooks/   on-call runbook (also the RAG corpus)
infra/      AWS CDK stack (TypeScript) + assertion tests
web/        operations dashboard (GitHub Pages)
docs/adr/   architecture decision records
```

## Limitations

* Telemetry comes from a simulator with a simple thermal and electrical model. It is not from real drive units.
* The fleet and incident listings scan their tables, which is fine for demo fleets. A per-site GSI is the documented next step ([ADR 0002](docs/adr/0002-dynamodb-data-model.md)).
* Robots authenticate with a single shared key. A production fleet would use per-device credentials, for example IoT Core with X.509 certificates.

## License

MIT
