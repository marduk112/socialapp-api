# Backend — LLM Instructions

Spring Boot 3.4, Java 21, Maven. Hexagonal architecture with 3 Bounded Contexts.

## Package structure

```
com.socialapp
├── config/                        # KafkaConfig, KafkaEventPublisher, Neo4jTransactionConfig, WebConfig
├── shared/
│   ├── domain/AggregateRoot.java  # extends Spring AbstractAggregateRoot
│   └── events/                    # DomainEvent (sealed), UserCreatedEvent, UserUpdatedEvent,
│                                  # PostCreatedEvent, ReactionEvent
├── usermanagement/
│   ├── domain/       User, UserId, UserRepository
│   ├── application/  UserService, CreateUserCommand, UpdateUserCommand
│   └── infrastructure/web/  UserController, UserPersistenceAdapter, UserJpaRepository
├── socialgraph/
│   ├── domain/       UserNode, UserNodeRepository
│   ├── application/  SocialGraphService, FollowUserCommand
│   └── infrastructure/messaging/  UserEventConsumer, SocialGraphController
└── feed/
    ├── domain/       Post, Comment, PostReaction, PostRepository, CommentRepository, PostReactionRepository
    ├── application/  PostService
    └── infrastructure/
        ├── web/      PostController
        ├── sse/      FeedSseService
        └── messaging/ PostEventConsumer
```

## Domain Events — how they work

1. `Post.create()` / `User.register()` calls `registerEvent(new XxxEvent(...))`
2. Spring fires `ApplicationEvent` after `@Transactional` method commits
3. `KafkaEventPublisher` catches via `@TransactionalEventListener(phase = AFTER_COMMIT)` and sends to Kafka
4. Consumers in other BCs process asynchronously

**Never publish to Kafka directly from domain or application layer.** Always go through `registerEvent()`.

## Kafka configuration rules

- All consumers must use `ErrorHandlingDeserializer` wrapping `JsonDeserializer`:
  ```java
  props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
  props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
  ```
- Trusted packages: `com.socialapp.*`
- Producer: `ACKS_CONFIG = all`, `ENABLE_IDEMPOTENCE_CONFIG = true`
- Topics: `user-events`, `post-events`, `reaction-events` (3 partitions, replication factor 1)

## Transaction manager rules

Two transaction managers exist:
- `JpaTransactionManager` — **@Primary**, used by default
- `Neo4jTransactionManager` — must be named explicitly

```java
// UserManagement / Feed services — no annotation needed (JPA is @Primary)
@Transactional

// SocialGraph services — always name it explicitly
@Transactional("neo4jTransactionManager")
```

If you add a new service in SocialGraph BC and forget the name, Spring will throw `NoSuchBeanDefinitionException`.

## Database migrations (Flyway)

Location: `src/main/resources/db/migration/`

Current migrations:
- `V1__init_users.sql` — users table
- `V2__init_posts.sql` — posts, post_reactions tables
- `V3__comments_sharing_editpost.sql` — comments table, sharing columns, updated_at
- `V4__add_password_hash.sql` — password_hash column on users
- `V5__add_original_content.sql` — original_content column on posts

**Always create a new numbered migration file. Never modify existing ones.**

## Password hashing

Uses BCrypt (`BCryptPasswordEncoder`). Do NOT change to SHA-512 — BCrypt is intentionally slow and salted. SHA-512 is inappropriate for passwords.

## SSE (Server-Sent Events)

`FeedSseService` manages `CopyOnWriteArrayList<SseEmitter>`. On `broadcast()`:
- Iterates all emitters
- Sends event with `EventBuilder.event("new-post")`
- Removes dead emitters on `SseEmitter.SseEventBuilderException`

Heartbeat emitter is sent on subscribe with event name `"connected"`. Do NOT change event names — frontend listens for exactly `"connected"` and `"new-post"`.

## Authentication

Currently session-less (no JWT/Spring Security). Login returns the full `User` object. Frontend stores it in localStorage via `CurrentUserService`. When adding security, keep backward compatibility with existing frontend service structure.

## Build

```bash
./mvnw spring-boot:run          # run with hot reload
./mvnw clean package -DskipTests # build jar
./mvnw test                     # run tests
```

Do NOT use `mvn` command directly — use the Maven wrapper `./mvnw`.
