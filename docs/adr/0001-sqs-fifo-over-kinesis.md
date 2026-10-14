# ADR 0001: SQS FIFO instead of Kinesis Data Streams for telemetry ingest

**Status:** accepted

## Context
Robots send a heartbeat every second. The detector is stateful per robot: EWMA baselines, CUSUM and stuck timers. It must see each robot's samples in order. The system must run inside the AWS free tier for demos and stay cheap at small scale.

## Options
| | Kinesis Data Streams | SQS FIFO | SQS standard |
|---|---|---|---|
| Per-robot ordering | per shard (partition key) | per message group | none |
| Idle cost | about $11/month for one provisioned shard; about $29/month for an on-demand stream | $0 | $0 |
| Free tier | none | 1M requests/month | 1M requests/month |
| Replay | yes (24 h to 365 d) | no | no |
| Lambda partial-batch retry | yes | yes | yes |
| Throughput ceiling | about 1 MB/s per shard | 3,000 msg/s per queue with batching; high-throughput mode up to 70k/s | effectively unlimited |

## Decision
Use SQS FIFO with `MessageGroupId = robotId` and `MessageDeduplicationId = robotId-seq`.

## Consequences
* Ordering per robot without managing shards. Different robots are still processed in parallel.
* Client retries within 5 minutes are de-duplicated by SQS. Anything older is caught by the conditional write on `seq` in DynamoDB, so processing is idempotent end to end.
* No stream replay. If replay becomes a requirement (for example, re-scoring history with a new detector), add a Kinesis Firehose to S3 tap or move to Kinesis. The ingest port (`TelemetryQueue`) makes that a one-class change.
* At about 3,000 heartbeats/s per queue this design serves about 3,000 robots per queue at 1 Hz. Beyond that, shard by site (one queue per site) or enable FIFO high-throughput mode.
