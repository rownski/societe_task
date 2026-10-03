# societe-task

Request lifecycle API starter using Java 21, Spring Boot 3.5, Maven, Spring Web,
Spring JDBC, Actuator, PostgreSQL 17, and JUnit 5 with Testcontainers.

## API contract and generated code

The source of truth is [`src/main/openapi/api.yaml`](src/main/openapi/api.yaml).
OpenAPI Generator runs during Maven's `generate-sources` phase, generating Spring
interfaces and DTOs into `target/generated-sources/openapi`. Maven automatically
compiles these sources. Generated files are not committed or edited by hand.
`ApplicationController` implements the generated `ApplicationsApi` interface.

```sh
./mvnw generate-sources
```

Flyway creates the database schema automatically at application startup. Applications
use UUID identifiers. `name`, `body`, and rejection `reason` must be nonblank strings.
Unknown JSON fields are rejected, so editing cannot change the name.

| Operation | Endpoint | Required input | Success |
| --- | --- | --- | --- |
| Create | `POST /applications` | JSON `name`, `body` | `201` with application |
| Edit body | `PATCH /applications/{id}` | JSON `body` | `200` with application |
| Reject | `PUT /applications/{id}/rejection` | JSON `reason` | `200` with application |
| Soft-delete | `DELETE /applications/{id}?reason=...` | Enum query parameter | `204` |

Deletion reasons: `DUPLICATE`, `CREATED_BY_MISTAKE`, `NO_LONGER_NEEDED` (case-sensitive).
Soft deletion preserves all data, the reason, and `deleted_at`. Repeated deletion
returns `204` without changing the original reason or timestamp. Editing or rejecting
a deleted application returns `404`. Unknown UUIDs return `404`; invalid UUIDs and
invalid input return `400`, using `application/problem+json` error responses.

Rejection stores `rejectionReason` and `rejectedAt`; repeating the same `PUT` does not
change timestamps. A later `PUT` can replace the rejection reason. Rejected applications
can still be edited or deleted. Mutations use transactional row locks to prevent edits
or rejection racing with deletion.

With the application running:

```sh
curl -X POST http://localhost:8080/applications \
  -H 'Content-Type: application/json' \
  -d '{"name":"My application","body":"Initial body"}'

# Set ID to the UUID returned by the create response.
ID='replace-with-application-uuid'

curl -X PATCH "http://localhost:8080/applications/$ID" \
  -H 'Content-Type: application/json' -d '{"body":"Updated body"}'

curl -X PUT "http://localhost:8080/applications/$ID/rejection" \
  -H 'Content-Type: application/json' -d '{"reason":"Missing documents"}'

curl -X DELETE "http://localhost:8080/applications/$ID?reason=DUPLICATE"
```

## Prerequisites

- JDK 21 or newer
- Docker running, with Docker Compose v2
- Internet access on the first build to download Maven, dependencies, and container images

Maven is provided through `./mvnw` (`mvnw.cmd` on Windows).

## Run locally with PostgreSQL in Docker

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

In another terminal, check application and database health:

```sh
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

The database is available at `localhost:5432`, database `societe_task`,
username `societe`, password `societe`. These defaults are for local development.
Database data persists in the `postgres-data` Docker volume.

Open a SQL console:

```sh
docker compose exec postgres psql -U societe -d societe_task
```

Stop PostgreSQL with `docker compose down`. To also delete its stored data, use
`docker compose down -v`.

### Configuration

| Environment variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/societe_task` |
| `DB_USERNAME` | `societe` |
| `DB_PASSWORD` | `societe` |
| `SERVER_PORT` | `8080` |
| `POSTGRES_PORT` | `5432` (Docker Compose host port only) |

For example, if port 5432 is already in use:

```sh
POSTGRES_PORT=5433 docker compose up -d --wait
DB_URL=jdbc:postgresql://localhost:5433/societe_task ./mvnw spring-boot:run
```

## Test with Testcontainers

With Docker running:

```sh
./mvnw test
```

Tests automatically start an isolated PostgreSQL container on a random port,
configure the datasource using Spring Boot's `@ServiceConnection`, and remove
the container afterward. Docker Compose does not need to be running. Tests
check the HTTP health endpoint, database connectivity, and application endpoints
(persistence, validation, immutable name, rejection, soft deletion, and idempotency)
using real PostgreSQL.
Docker is required; database tests are not silently skipped when it is unavailable.

## Build

```sh
./mvnw clean verify
```

With the local database running, launch the packaged application:

```sh
java -jar target/societe-task-0.0.1-SNAPSHOT.jar
```
