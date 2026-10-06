# Smart Home telemetry

Java 21, Spring Boot 3.3.2, Kafka, Avro, Protobuf/gRPC, PostgreSQL.

## History and branch sequence

The original `main` is `ff11437af44c528d4d7dee875b8afebfd9894b22` (initial scaffold).
The accepted technical baseline is `2af804eb303f1a7dbf194ed773403b6f7f6a4708`
from `origin/1-collector-json`, as explicitly confirmed by the repository owner.
`develop` was created directly from that commit; Sprint 19 history was preserved.
PR #2 and `main` were not changed.

- `2-collector-grpc`: `e1e1784`, migrated Collector; fast-forwarded into `develop`.
- `3-aggregator`: `c63f9a3`, added Aggregator; fast-forwarded into `develop`.
- `4-analyzer`: based on `c63f9a3`; kept separate from `develop` for manual review.

## Pipeline and contracts

| Component | Input | Output / consumer group |
| --- | --- | --- |
| Collector | gRPC `localhost:59091` | `telemetry.sensors.v1`, `telemetry.hubs.v1` |
| Aggregator | `telemetry.sensors.v1` | `telemetry.snapshots.v1`; group `telemetry-aggregator` |
| Analyzer hub processor | `telemetry.hubs.v1` | PostgreSQL; group `telemetry-analyzer-hubs` |
| Analyzer snapshot processor | `telemetry.snapshots.v1` | Hub Router gRPC; group `telemetry-analyzer-snapshots` |

All telemetry records are raw Avro binary, with `hubId` as Kafka key.
The host applications use `localhost:9092`; Docker clients use `kafka:29092`.
`KAFKA_BOOTSTRAP_SERVERS` overrides the host address.

Protobuf files are under `serialization/proto-schemas/src/main/protobuf/telemetry`.
Maven generates the message, OrBuilder and gRPC classes at compile time; generated Java is not tracked.
The Java event package is `ru.yandex.practicum.grpc.telemetry.event`.

- `telemetry.service.collector.CollectorController` exposes `CollectSensorEvent` and `CollectHubEvent`.
  Its Java package is `ru.yandex.practicum.grpc.telemetry.collector`.
- `telemetry.service.hubrouter.HubRouterController` exposes `handleDeviceAction`.
  Its Java package is `ru.yandex.practicum.grpc.telemetry.hubrouter`.
  Analyzer connects to `static://localhost:59090` with a configurable RPC deadline.

Collector has separate transport, mapping and Kafka producer classes. It acknowledges an RPC only
once Kafka acknowledges the record. Missing payloads, invalid timestamps and unknown enums produce
`INVALID_ARGUMENT`; publication failures produce `UNAVAILABLE` and are logged.
Boolean/integer condition values and absent/zero action values remain distinct.
The obsolete HTTP controllers and DTOs were removed.

The inherited `TemperatureSensorAvro` included identity/timestamp fields that were absent from the
Hub Router wire schema. Its payload now contains only Celsius/Fahrenheit values, matching the fixture.

Aggregator keeps a snapshot per hub. A strictly older event or unchanged payload is ignored.
A changed payload at an equal timestamp is accepted: Protobuf nanoseconds are truncated by Avro
`timestamp_ms`. This behavior was confirmed by the owner and the official Hub Router implementation.
Published snapshots are separate objects, so subsequent updates do not mutate an already submitted snapshot.

Both applications disable automatic offset commits. Offsets advance only after successful handling;
Aggregator also waits for Kafka publication. Normal processing uses asynchronous commits; revocation
and shutdown commit processed offsets synchronously. Shutdown wakes the consumer and closes resources
on the consumer's own thread. Analyzer owns two independent consumers and stops on an unrecoverable
processing error without committing the failed record.

## PostgreSQL

The supplied repository contained no course Analyzer SQL. `docker/init-databases.sql` creates only
commerce databases. The minimal Analyzer schema is in `analyzer/src/main/resources/schema.sql`.
Spring initializes it idempotently and Hibernate validates it (`ddl-auto=validate`).

- `sensors`: device identity/type; unique `(hub_id, device_id)`.
- `scenarios`: unique `(hub_id, name)`.
- `conditions`: scenario/device foreign keys, condition type, operation, nullable integer value.
  Boolean values use 0/1, matching the course scenario model.
- `actions`: scenario/device foreign keys, action type and nullable integer value.

Hub events are transactional. Repeated additions update existing objects; repeated removals are safe.
Scenario replacement removes obsolete conditions/actions. Device references must belong to the same hub.
Removing a device removes dependent scenarios as a whole rather than weakening their conditions.
All conditions must match. Missing sensor readings do not match; an incompatible payload is rejected as invalid.

Connection defaults: database `telemetry_analyzer`, port `5432`, user `dbuser`, password `12345`.
Override with `ANALYZER_DB_URL`, `ANALYZER_DB_USER`, `ANALYZER_DB_PASSWORD`.

## Build and run

From the repository root:

```sh
mvn -B -pl telemetry/collector,telemetry/aggregator,telemetry/analyzer -am clean verify
docker compose up -d kafka postgres kafka-init-topics
java -jar telemetry/collector/target/collector-1.0-SNAPSHOT.jar
java -jar telemetry/aggregator/target/aggregator-1.0-SNAPSHOT.jar
java -jar telemetry/analyzer/target/analyzer-1.0-SNAPSHOT.jar
```

Run applications in separate terminals. Do not rebuild an executable JAR while a JVM is using it;
stop the process first or run a separate copy of the built JAR.

The official fixture is read-only. From `hub-router/`:

```sh
bash scripts/macos_linux/2-collector-grpc-tests.sh
bash scripts/macos_linux/3-aggregatorr-tests.sh
bash scripts/macos_linux/4-analyzer-tests.sh
```

The doubled `r` in the Aggregator script name is intentional. Use only Collector for stage 2,
Collector + Aggregator for stage 3, and all three applications for stage 4.
Stop processing applications before resetting test data. For an independent test run, delete and
recreate only `telemetry.sensors.v1`, `telemetry.hubs.v1`, `telemetry.snapshots.v1`, with one partition
and replication factor one. If database cleanup is necessary, restrict it to the four Analyzer tables
in `telemetry_analyzer`. No other database or Docker volume is needed for this test.

PostgreSQL-backed persistence tests (transactions roll back):

```sh
mvn -B -pl telemetry/analyzer -am verify \
  -Danalyzer.test.database=jdbc:postgresql://localhost:5432/telemetry_analyzer
```

## Validation and practical limits

- Telemetry clean verify: passed, 16 tests covering mapping, aggregation, rules, persistence and offset lifecycle.
- Persistence suite against PostgreSQL 16.1: passed.
- Official Hub Router stages 2, 3 and 4: zero errors and exit code zero.
- Root `mvn -B -DskipTests package`: passed across all modules.
- Root `mvn -B verify`: telemetry passed; existing Inventory acceptance tests failed (HTTP 500 instead
  of 201/400/409). Commerce implementations are pre-existing scaffolds and outside this telemetry sprint.
- Analyzer restart recovery: PostgreSQL retained 9 devices, 3 scenarios, 4 conditions and 5 actions;
  two pending snapshots were replayed and consumer lag returned to zero using a temporary local gRPC receiver.
- Hub Router source, scripts and JAR were not modified. Build output and runtime logs are not committed.

Delivery is at-least-once. A crash between a successful external action and offset commit can repeat
an action; the supplied gRPC contract provides no idempotency token. When the Hub Router test exits,
its server disappears. A pending action can therefore stop Analyzer with an explicitly logged
`UNAVAILABLE`; restarting with a reachable router replays the uncommitted snapshot.

Aggregator snapshots are in memory as requested. Restarting from committed Kafka offsets does not
restore earlier sensor states; devices must report again. Durable state restoration and distributed
exactly-once processing are outside this implementation. Independent hub/snapshot topics also do not
provide a shared ordering guarantee; scenario configuration should precede sensor processing.

The repository workflow triggers on pull requests or manual dispatch, not branch pushes. No PR was
created and no workflow was dispatched by this task; CI status must be checked when the owner opens
`4-analyzer` -> `develop`.
