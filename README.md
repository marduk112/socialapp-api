# socialapp-api

Spring Boot backend of [SocialApp](https://github.com/marduk112/SocialApp): a social network built around **hexagonal architecture, DDD and event-driven design** with polyglot persistence.

- **Java 25, Spring Boot 4.1** (Spring Framework 7, Security 7, Data 2025.1)
- **PostgreSQL 18** (users, feed, notifications; Flyway migrations) and **Neo4j 5.26** (follow graph)
- **Kafka** between four bounded contexts that never import each other, with a **transactional outbox** (Postgres, plus a Neo4j-side outbox)
- JWT authentication, SSE streams (live feed, notifications), keyset pagination
- Full-text post search, people search (`pg_trgm`), trending hashtags, friends-of-friends suggestions
- ~570 tests: unit, web slices, and Testcontainers integration tests against real PostgreSQL, Neo4j and Kafka

The architecture, conventions and design decisions are documented in [CLAUDE.md](CLAUDE.md).

## Run

The infrastructure (PostgreSQL, Neo4j, Kafka) comes from the [SocialApp](https://github.com/marduk112/SocialApp) repository:

```bash
docker compose up -d          # in the SocialApp repo
cd backend
./mvnw spring-boot:run        # http://localhost:8080
```

`APP_JWT_SECRET` (at least 32 bytes) should be set outside local development; a clearly marked development-only default is used otherwise.

## Test

```bash
./mvnw verify                 # needs Docker (Testcontainers) and JDK 25
```

## Frontend

[socialapp-web](https://github.com/marduk112/socialapp-web) (Angular 22).

## License

[MIT](LICENSE)
