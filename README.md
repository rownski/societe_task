# societe-task

This POC demonstrates an application lifecycle API, prioritizing correct state
transitions, safe concurrent updates, and traceable changes over architectural
complexity. OpenAPI makes the contract explicit, separate domain/API/persistence
packages keep responsibilities clear, and transactions keep state changes and
audit history consistent. PostgreSQL-backed Testcontainers tests verify real
behavior. The scope is deliberately focused; production concerns such as
authentication are not implemented.

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

### Package structure

Handwritten code lives under `src/main/java/com/societe/task/application`:

```text
application/
├── domain/          Application, ApplicationState, exception/
├── service/         ApplicationService, ApplicationPage
├── persistence/     JdbcApplicationRepository, JdbcApplicationStateHistoryRepository
└── api/             ApplicationController, ApplicationMapper, ApplicationExceptionHandler
```

OpenAPI Generator creates `ApplicationsApi` in
`com.societe.task.application.api` and request/response DTOs in
`com.societe.task.application.api.dto`, under `target/generated-sources/openapi`.
Change the contract or generator configuration in `pom.xml`, not generated files.
Domain models remain separate from HTTP DTOs. The API mapper converts between
them, and the exception handler translates business exceptions into HTTP problem
responses. The service owns transactions and lifecycle operations; persistence
owns SQL and row mapping.

Tests mirror the feature packages: `api` contains lifecycle, listing, and
publication integration tests; `service` tests audit transaction guarantees;
`domain` contains state-policy tests;
`persistence` contains schema tests. Shared API test setup lives in `support`,
with a Spring-managed PostgreSQL container reused across the API test classes.

Applications use UUID identifiers. `name`, `body`, and rejection `reason` must be
nonblank strings. Unknown JSON fields are rejected: clients cannot change the name,
choose the initial state, set a publication number, or modify the state through a body
edit. Create, edit, listing items, and successful PUT responses include the application's `state`.

## Application lifecycle

```text
CREATED ──→ VERIFIED ──→ ACCEPTED ──→ PUBLISHED
   │            │            │
   ▼            ▼            ▼
DELETED      REJECTED      REJECTED
```

| Current state | Allowed next states | Body editable? |
| --- | --- | --- |
| `CREATED` | `VERIFIED`, `DELETED` | Yes |
| `VERIFIED` | `ACCEPTED`, `REJECTED` | Yes |
| `ACCEPTED` | `PUBLISHED`, `REJECTED` | No |
| `PUBLISHED` | None | No |
| `DELETED` | None | No |
| `REJECTED` | None | No |

Creation always assigns `CREATED`. Only the transitions above are allowed; steps
cannot be skipped or reversed. `PUBLISHED`, `DELETED`, and `REJECTED` are terminal.
Body edits do not change the state, and the name is always immutable.

### Endpoints

| Operation | Endpoint | Allowed source state | Required input | Success |
| --- | --- | --- | --- | --- |
| List | `GET /applications` | — | Optional `name`, `state`, `page`, `size` queries | `200` with a page of applications |
| Create | `POST /applications` | — | JSON `name`, `body` | `201` with application in `CREATED` |
| Edit body | `PATCH /applications/{id}` | `CREATED`, `VERIFIED` | JSON `body` | `200` with application |
| Verify | `PUT /applications/{id}/verification` | `CREATED` | No body | `200` with application in `VERIFIED` |
| Accept | `PUT /applications/{id}/acceptance` | `VERIFIED` | No body | `200` with application in `ACCEPTED` |
| Publish | `PUT /applications/{id}/publication` | `ACCEPTED` | No body | `200` with application in `PUBLISHED` and its `publicationNumber` |
| Reject | `PUT /applications/{id}/rejection` | `VERIFIED`, `ACCEPTED` | JSON `reason` | `200` with application in `REJECTED` |
| Soft-delete | `DELETE /applications/{id}?reason=...` | `CREATED` | Enum query parameter | `204` |

Deletion reasons: `DUPLICATE`, `CREATED_BY_MISTAKE`, `NO_LONGER_NEEDED` (case-sensitive).
Soft deletion retains the row and all its data, storing the reason and `deleted_at`.
Rejection stores `rejectionReason` and `rejectedAt`; these cannot be replaced after
rejection. Mutations use transactional row locks so concurrent requests validate
against the latest state and cannot bypass transition rules.

### State-change audit history

`application_state_history` stores one entry for every creation and successful
state transition. Each entry contains an identity `id`, `application_id`,
`previous_state`, `new_state`, `changed_at`, and optional `reason`.
Creation records `NULL → CREATED`; rejection records the JSON reason, and soft
deletion records the deletion enum value. Other transitions have no reason.

The service inserts history in the same transaction as the application mutation:
both commit or both roll back, including publication. Retries, invalid/forbidden
transitions, and body edits do not add history. Existing row locks ensure concurrent
requests cannot record the same successful transition twice.

History is append-only through the application, with no update/delete operations
or HTTP history endpoint. Soft deletion retains all entries, and a foreign key
prevents hard-deleting an application while its history exists. This is not a
tamper-proof audit store: direct SQL changes bypass service auditing, and privileged
database users can modify history. There is no actor field because the POC has no
authentication.

Inspect an application's history in the SQL console:

```sql
SELECT previous_state, new_state, changed_at, reason
FROM application_state_history
WHERE application_id = '9c4b8206-1a2a-4e31-a7b1-58f790dabc02'
ORDER BY id;
```

Use `id` to order entries within an application. `changed_at` is the PostgreSQL
transaction timestamp, not commit order; entries can share a timestamp.

### Operational logging

Spring Boot's SLF4J/Logback logging writes to the console. No separate log database
or file appender is configured; the runtime/deployment platform can collect output.

- `INFO`: creation (UUID and state) and successful transitions (UUID, previous/new
  states), emitted only **after the enclosing transaction commits**.
- `DEBUG`: rejected transitions, forbidden body edits, and missing applications.
- `ERROR`: unexpected HTTP failures, centrally logged with exception types and stack
  frames. Exception messages and suppressed exceptions are omitted because database
  or validation messages can contain sensitive values. Cause chains are bounded.
  Clients receive a generic problem response; normal client errors keep their
  `400`/`404`/`405`/`409`/`415` statuses and are not logged as unexpected failures.

These application logs do not include names, bodies, rejection/deletion reasons,
credentials, query strings, or complete request/response payloads. Successful body
edits and repository calls are not logged individually. Database audit history
remains authoritative: console logs are diagnostic and do not replace it.

The feature package defaults to `INFO`. Enable its debug logs locally with:

```sh
APPLICATION_LOG_LEVEL=DEBUG ./mvnw spring-boot:run
```

Avoid enabling broad Spring/JDBC request or SQL debug logging when handling
sensitive data; third-party logger output is outside these application safeguards.

### Publication numbers

Successful `ACCEPTED → PUBLISHED` publication assigns a unique positive numeric
`publicationNumber` (`int64`). Before publication this field is `null`; publication
responses and listing items expose it after publication. The original UUID remains
unchanged and is still used in endpoint URLs.

For example, a published application's response includes:

```json
{
  "id": "9c4b8206-1a2a-4e31-a7b1-58f790dabc02",
  "state": "PUBLISHED",
  "publicationNumber": 123
}
```

The number and state are stored together in one transaction after validating the
transition under a row lock. PostgreSQL's `application_publication_number_seq`
starts at `1` and generates numbers safely across concurrent publications. Database
constraints require numbers to be unique, positive, and present **only** in `PUBLISHED`.

Numbers are not guaranteed to be consecutive: a transaction that rolls back can
consume a sequence value without publishing an application. Gaps are acceptable.
`PUBLISHED` remains terminal: the body and number cannot be changed, and rejection,
deletion, or any further transition returns `409`. Repeated publication preserves
the original number and does not allocate another sequence value.

### Errors and retries

Errors use `application/problem+json` (RFC 9457):

- `400 Bad Request`: missing/invalid input, unsupported deletion reason, invalid UUID,
  unsupported state, invalid pagination, or unexpected JSON fields.
- `404 Not Found`: unknown application, or editing a deleted application.
- `409 Conflict`: forbidden or repeated state transition, or a body edit outside
  `CREATED`/`VERIFIED` (except deleted applications, which return `404`).

Repeating a successful transition is **not** treated as success: it returns `409`
explaining that the application has already been changed or is in the wrong state.
This includes repeated rejection and deletion, even with the same reason. Conflicts
do not change state, body, reasons, or timestamps. PUT/DELETE retries therefore have
no additional side effects, although their response differs from the first request.

## Listing and pagination

```http
GET /applications?name=loan&state=VERIFIED&page=0&size=10
```

| Query parameter | Behavior | Default |
| --- | --- | --- |
| `name` | Case-insensitive literal substring; blank values impose no restriction | No name filter |
| `state` | Exact, case-sensitive `ApplicationState` enum value | All states except `DELETED` |
| `page` | Zero-based page index, at least `0` | `0` |
| `size` | Page size from `1` to `100` | `10` |

Filters combine with **AND**. `%`, `_`, and backslashes in the name filter are
literal characters, not search wildcards. Deleted applications are returned only
when explicitly requesting `state=DELETED`; a name filter alone never includes them.
Results are ordered by `created_at DESC, id DESC` (newest first, UUID tie-breaker).
Count and page content use the same database snapshot within each listing request;
separate requests can still reflect changes made between pages.

The response contains `content` (application objects), `page`, `size`,
`totalElements` (all matching rows, not just this page), and `totalPages`. Empty
matches return zero totals. A page beyond the results returns empty `content`
while preserving the matching totals. Both cases return `200`, not `404`.

```json
{
  "content": [],
  "page": 0,
  "size": 10,
  "totalElements": 0,
  "totalPages": 0
}
```

```sh
# First page, default size 10; excludes DELETED.
curl 'http://localhost:8080/applications'

# Second page of verified applications whose names contain "loan".
curl --get 'http://localhost:8080/applications' \
  --data-urlencode 'name=loan' --data-urlencode 'state=VERIFIED' \
  --data-urlencode 'page=1' --data-urlencode 'size=10'

# Explicit access to soft-deleted applications.
curl 'http://localhost:8080/applications?state=DELETED'
```

## API examples

With the application running, use these shell helpers. These examples require
`jq` to extract the UUID; each workflow creates a separate application.

```sh
BASE_URL=http://localhost:8080
create_application() {
  curl --fail-with-body -sS -X POST "$BASE_URL/applications" \
    -H 'Content-Type: application/json' \
    -d '{"name":"My application","body":"Initial body"}' | jq -er '.id'
}
```

### Create → verify → accept → publish

```sh
ID=$(create_application)
curl --fail-with-body -sS -X PATCH "$BASE_URL/applications/$ID" \
  -H 'Content-Type: application/json' -d '{"body":"Edited while CREATED"}'
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/verification"
curl --fail-with-body -sS -X PATCH "$BASE_URL/applications/$ID" \
  -H 'Content-Type: application/json' -d '{"body":"Edited while VERIFIED"}'
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/acceptance"
# The publication response contains the server-assigned publicationNumber.
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/publication"

# Repeating publication returns 409 Conflict, without changing data.
curl -i -X PUT "$BASE_URL/applications/$ID/publication"
```

### Create → delete

```sh
ID=$(create_application)
curl --fail-with-body -sS -X DELETE "$BASE_URL/applications/$ID?reason=DUPLICATE"
```

### Create → verify → reject

```sh
ID=$(create_application)
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/verification"
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/rejection" \
  -H 'Content-Type: application/json' -d '{"reason":"Missing documents"}'
```

### Create → verify → accept → reject

```sh
ID=$(create_application)
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/verification"
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/acceptance"
curl --fail-with-body -sS -X PUT "$BASE_URL/applications/$ID/rejection" \
  -H 'Content-Type: application/json' -d '{"reason":"Not eligible for publication"}'
```

## Database migrations

Spring Boot auto-configures Flyway using `spring.datasource`. At startup, Flyway
runs pending migrations from `src/main/resources/db/migration` in version order
and records them in `flyway_schema_history`:

- `V1__create_applications.sql`: creates the complete application table, including
  the state column with a `CREATED` default, metadata fields, publication number sequence,
  and constraints enforcing valid states, matching rejection/deletion metadata, and
  positive unique numbers present exactly in the `PUBLISHED` state.
- `V2__create_application_state_history.sql`: adds the state-change history table,
  its constraints, and an index on `(application_id, id)`. V1 remains unchanged.

For a database using the current V1, restarting the application applies V2 without
resetting data. Existing applications are preserved and receive history only on
subsequent transitions; historical transitions are not backfilled or invented.

Earlier POC development consolidated schema changes into V1. If your local database
still uses an older V1 checksum or an obsolete V2, Flyway will report validation
errors. Only for disposable development data, you can recreate that database:

**The following reset deletes all data in this project's Docker Compose volumes.**
Stop the application first, then run:

```sh
docker compose down -v
docker compose up -d --wait
./mvnw spring-boot:run
```

Do not edit applied migrations: Flyway validates their checksums. Add V3 and later
migrations for subsequent changes instead of resetting databases whose data needs
to be preserved.

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
| `APPLICATION_LOG_LEVEL` | `INFO` (application feature logs) |
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
check the HTTP health endpoint and application endpoints using real PostgreSQL:
persistence, validation, immutable name, all allowed transitions and representative
forbidden transitions, state-based edit restrictions, mandatory reasons, retry
conflicts, and concurrent verification/deletion. Publication tests additionally
check number assignment, listing responses, terminality, transaction rollback, and
concurrent publication of the same or different applications. Listing tests use
direct database fixtures and cover pagination, name/state filters, deleted-row
visibility, deterministic ordering, and query validation.

Migration tests cover fresh V1/V2 creation, upgrading an existing V1 database without
backfilling history, the default state, metadata/publication constraints, and audit
constraints. Audit tests check creation and every allowed transition, reasons,
retry/concurrency behavior, rollback, and atomicity when the history insert fails.
Logging tests check after-commit timing, suppression on rollback, debug/error
levels, sensitive-value exclusion, and preservation of normal HTTP error statuses.
Cheap state-policy unit tests exhaustively cover
all 36 source/target combinations and body edit eligibility; API tests focus on
HTTP/persistence behavior rather than repeating the entire policy matrix.
Docker is required; database tests are not silently skipped when it is unavailable.

## Build

```sh
./mvnw clean verify
```

With the local database running, launch the packaged application:

```sh
java -jar target/societe-task-0.0.1-SNAPSHOT.jar
```
