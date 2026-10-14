# ADR 0002: DynamoDB data model

**Status:** accepted

Three tables, each with one access pattern that matters:

| Table | Key | Access pattern | Notes |
|---|---|---|---|
| RobotState | `robotId` | read and conditionally overwrite latest state + detector stats | `ConditionExpression: attribute_not_exists(robotId) OR seq < :seq` makes writes idempotent and rejects late, out-of-order samples |
| Telemetry | `robotId`, `sk = ts#seq` | last N minutes for one robot | TTL `expiresAt` deletes items after 7 days at no cost |
| Incidents | `id`; sparse GSI `openKey = robotId#kind` | is there an OPEN incident of this kind for this robot? | `openKey` exists only while the incident is open, so resolving removes it from the index and the index stays small |

The detector state lives with the robot's latest heartbeat, so a stateless Lambda resumes exactly where the previous invocation stopped, and a single conditional write makes state and statistics consistent.

**Known limit:** `GET /fleet` and `GET /incidents` scan their tables. That is fine for the demo fleet. At warehouse scale, add a `site` GSI (`site`, `lastSeenAt`) and query per site.
