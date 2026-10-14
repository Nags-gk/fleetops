# ADR 0003: Java 21 on Lambda with SnapStart

**Status:** accepted

* **Why Lambda rather than ECS:** traffic is bursty and starts at zero. Lambda scales per message group and costs nothing when idle. The processor is I/O-bound on DynamoDB.
* **Why Java:** the domain code (detector, incident lifecycle) benefits from records, sealed types and a mature AWS SDK. It is also the team language of most robotics backend services.
* **Cold starts:** the API-facing functions (ingest, query) use SnapStart on published versions behind a `live` alias, with tiered compilation stopped at C1. The queue-driven processor tolerates cold starts and does not need SnapStart.
* **No framework:** plain `RequestHandler`s and the AWS SDK v2 URL-connection HTTP client keep the deployment package and init time small. Spring Boot was considered and rejected for this function size.
