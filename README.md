# societe-task

Request lifecycle API starter using Java 21, Spring Boot 3.5, Maven, Spring Web,
Spring JDBC, Actuator, PostgreSQL 17, and JUnit 5 with Testcontainers.

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
check the HTTP health endpoint and a database write/read using real PostgreSQL.
Docker is required; database tests are not silently skipped when it is unavailable.

## Build

```sh
./mvnw clean verify
```

With the local database running, launch the packaged application:

```sh
java -jar target/societe-task-0.0.1-SNAPSHOT.jar
```
