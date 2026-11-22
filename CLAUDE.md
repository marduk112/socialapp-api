# Backend — LLM Instructions

Spring Boot 4.1, Java 25, Maven. Hexagonal architecture with 4 Bounded Contexts (UserManagement, SocialGraph, Feed,
Notification).

## Package structure

```
com.socialapp
├── config/                        # KafkaConfig, Neo4jTransactionConfig, WebConfig, ValidationConfig, security/
│   ├── outbox/                    # transactional outbox: OutboxEventWriter, OutboxRelay, OutboxCleanup,
│   │                              # OutboxImporter (idempotent import of events recorded in another store)
│   └── backfill/                  # StartupBackfill (contract), StartupBackfillRunner, BackfillStateRepository (V19)
├── shared/
│   ├── domain/AggregateRoot.java  # extends Spring AbstractAggregateRoot
│   └── events/                    # DomainEvent (sealed), UserCreatedEvent, UserUpdatedEvent, PostCreatedEvent,
│                                  # ReactionEvent, UserFollowedEvent, UserUnfollowedEvent, CommentCreatedEvent,
│                                  # UserBlockedEvent, UserUnblockedEvent, UserMutedEvent, UserUnmutedEvent (5A),
│                                  # Snippet (<= 120 code point plain-text excerpt carried by events)
├── usermanagement/
│   ├── domain/       User, UserId, UserRepository, UserBlockRepository (port, 5A)
│   ├── application/  UserService, CreateUserCommand, UpdateUserCommand, UserBlockProjection (5A)
│   └── infrastructure/  web/ UserController, UserSearchController   persistence/ UserPersistenceAdapter,
│                        UserJpaRepository, JdbcUserBlockRepository   messaging/ BlockEventConsumer (social-events, 5A)
├── socialgraph/
│   ├── domain/       UserNode, UserNodeRepository (FOLLOWS, BLOCKS, MUTES Cypher)
│   ├── application/  SocialGraphService (follow, block, mute, relationship), FollowUserCommand,
│   │                 FollowGraphChanged (in-process signal)
│   └── infrastructure/
│       ├── messaging/  UserEventConsumer          web/  SocialGraphController
│       ├── outbox/     SocialOutboxStore, SocialOutboxForwarder (Neo4j outbox -> Postgres outbox)
│       └── backfill/   FollowProjectionBackfill, FollowBackfillStore (pre-2A follows -> social-events)
└── feed/
    ├── domain/       Post, PostType, Comment, PostReaction, Follow, Bookmark (+ repositories), HashtagParser, MentionParser,
    │                 Mention, PostHashtagRepository / PostMentionRepository (ports), PostMedia + PostMediaRepository,
    │                 ImageFormat (3C), BlockMuteRepository (port, 5A)
    ├── application/  usecase/  PostService, PostView, AuthorProfileProjection, FollowProjection,
    │                           MentionLookup, PostTextIndexBackfill (pre-2B hashtags/mentions), BookmarkService (3B),
    │                           BlockMuteProjection, PostVisibility (5A)
    │                 timeline/ TimelineService, FeedCursor, FeedScope, FeedPage, FolloweeLookup, BlockMuteLookup (5A)
    │                 media/    MediaService, ImageInspector, MediaStorage (port), MediaView, MediaProperties,
    │                           MediaRejectedException (3C)
    └── infrastructure/
        ├── web/      PostController, PostResponse (REST + SSE `original` shape), MaxCodePoints, MediaController
        ├── media/    LocalDiskMediaStorage, MediaFileController (GET /uploads/media/**), MediaCleanupJob, MediaConfig
        ├── persistence/ JdbcPostHashtagRepository, JdbcPostMentionRepository, JdbcBlockMuteRepository (JDBC, same JPA tx)
        ├── sse/      FeedSseService
        └── messaging/ PostEventConsumer, UserEventConsumer, SocialEventConsumer
└── notification/                  # stage 3A, see "Notifications"
    ├── domain/       Notification (aggregate, dedupe/toggle rules), NotificationType, NotificationIntent,
    │                 NotificationPolicy (pure: who is notified), ActorProfile (+ repositories)
    ├── application/  NotificationService, ActorProfileProjection, ActorRestrictionProjection (5A), NotificationCursor,
    │                 NotificationPage, NotificationView, ports NotificationQueries / NotificationCutoff / ActorRestrictions
    └── infrastructure/
        ├── web/         NotificationController, NotificationResponse (REST + SSE shape)
        ├── sse/         NotificationSseService (per-user streams)
        ├── persistence/ JdbcNotificationQueries (read side, LEFT JOIN profiles), JdbcNotificationCutoff,
        │                JdbcActorRestrictions (5A)
        └── messaging/   NotificationEventConsumer (5 listeners, one per topic)
```

## Domain events / Outbox

Every event that UserManagement and Feed publish (topics `user-events`, `post-events`, `reaction-events`, `comment-events`)
goes through a **transactional outbox** (`config/outbox`, BC-neutral). SocialGraph's events (`social-events`)
reach the same outbox through a Neo4j-side outbox, see "SocialGraph events" below.

1. The aggregate calls `registerEvent(new XxxEvent(...))`; Spring Data publishes it on `repository.save()`.
2. `OutboxEventWriter` (`@TransactionalEventListener(BEFORE_COMMIT)`) inserts it into `outbox_events`
   (V14) **in the same JPA transaction** - commit = aggregate + event durable, rollback = neither.
   It flushes JPA first, so the aggregate's row lock is taken before the outbox id is drawn.
3. `OutboxRelay` (`@Scheduled`, `app.outbox.poll-interval-ms`, plus an immediate wake-up after each commit)
   takes `pg_try_advisory_xact_lock` (one publishing instance at a time), reads unpublished rows **in id
   order**, sends each synchronously via the regular `KafkaTemplate<String, DomainEvent>` (same topic, key
   and JSON as before) and sets `published_at`. The first failure stops the batch (nothing overtakes it),
   `attempts`/`last_error` are recorded, retried next poll. At-least-once: a crash mid-batch re-sends.
4. `OutboxCleanup` deletes published rows older than `app.outbox.retention-days`.

Failures: broker/infrastructure errors block the relay (order kept, ERROR every `max-attempts` failures),
never park. Row-specific errors (unreadable payload, serialization, record too large) park the row after
`max-attempts` (`parked_at`, ERROR); a parked row holds back only later rows with the **same topic+key**.
Repair: fix the row and set `parked_at = NULL` (retried), or set `published_at` to skip it deliberately.
SKIP LOCKED multi-publisher was rejected because it breaks per-key order.

Config (`app.outbox.*`): `poll-interval-ms` (300), `batch-size` (100), `max-attempts` (10),
`send-timeout-ms` (5000, also producer `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`),
`retention-days` (7), `cleanup-interval-ms` (3600000), `cleanup-batch-size` (10000).
Shutdown: relay and cleanup stop on `ContextClosedEvent`; the relay interrupts a run in progress, so a
blocked send/connection never delays shutdown (unstamped rows are re-published later).

**Never publish to Kafka directly from domain or application layer.** Always go through `registerEvent()`,
inside a JPA `@Transactional` method (or, for Neo4j state, the Neo4j outbox below). Adding an event type: add
it to the sealed `DomainEvent` (+ `@JsonSubTypes`), the (exhaustive) switch in `OutboxEventWriter.routeOf`, and the
exhaustive switches of every consumer (`UserEventConsumer` x2, `SocialEventConsumer`, `NotificationEventConsumer`,
`BlockEventConsumer`) and, for a SocialGraph event, `SocialOutboxForwarder.toEvent`.
Wire formats are append-only: new fields go at the end, readers must accept their absence (null / empty).

### Feed events for notifications (stage 3A)

- `CommentCreatedEvent` (eventId, occurredAt, commentId, postId, postAuthorId, parentCommentId, parentCommentAuthorId,
  authorId, authorUsername, contentSnippet) -> NEW topic `comment-events`, key = postId, aggregateType `Comment`.
  Registered by `Comment.create(post, parent, ...)` (`Comment` is now an `AggregateRoot`), published on save. Replies
  carry the parent and its author; `contentSnippet` = `Snippet.of(text)` at creation (not updated on edit).
- `PostCreatedEvent` + `originalAuthorId`, `originalContent` (REPOST/QUOTE: author and text of the target; null for a
  POST) and `mentionedUserIds` (resolved mentions of the post's own text, never the author; `[]` when none or for old
  events). `PostService.saveWithTextIndex` resolves mentions BEFORE `save()` and calls `Post.announceMentions`, which
  replaces the pending creation event (Spring Data publishes on `save()`). Edits do not announce new mentions.
- `ReactionEvent` + `target` (`POST` default | `COMMENT`), `commentId`, `targetOwnerId` (author of the reacted
  post/comment), `targetSnippet`. **Comment reactions are published since 3A** (`Comment.react(userId, ...)`) on
  `reaction-events` with `postId` = the comment's post (same key), aggregateType `Comment`. Old events deserialize with
  `target = POST` and nulls. Consumers that only want post reactions must check `target`.

### SocialGraph events (Neo4j state, Postgres outbox)

`UserFollowedEvent` / `UserUnfollowedEvent` (eventId, occurredAt, followerId, followeeId) -> topic
`social-events`, key = followerId. There is no transaction spanning Neo4j and Postgres, so:

1. `UserNodeRepository.follow/unfollow` (one Cypher statement, one Neo4j tx) write-locks the follower node,
   creates/deletes the FOLLOWS relationship **only if that changes the graph**, and in the same statement
   creates a `:SocialOutbox` node (eventId, type, followerId, followeeId, occurredAt). A no-op follow/unfollow
   matches nothing, records nothing and returns empty (`SocialGraphService` returns false).
   `occurredAt` = per-follower logical clock `u.socialClock = max(now, previous + 1)` (epoch ms) advanced
   under the lock: strictly increasing in graph commit order per follower.
2. `SocialOutboxForwarder` (`@Scheduled` at `app.outbox.poll-interval-ms`, plus an AFTER_COMMIT wake-up via
   the in-process `FollowGraphChanged` signal) reads pending nodes oldest first, calls
   `OutboxImporter.importBatch` (one JPA tx, advisory lock, `INSERT ... ON CONFLICT (event_id) DO NOTHING`),
   and deletes the nodes **only after** that commit. Then `OutboxRelay` publishes as usual.
3. Guarantee: a committed graph change always yields exactly one outbox row (Kafka: at-least-once); a
   rolled-back change yields none. A crash/outage between any two steps only delays the event (Neo4j node or
   outbox row is retried; re-import is a no-op). Residual: lag while Postgres/Kafka are down; if a node is
   re-imported after its published row was purged (> `retention-days`) it is published twice (harmless).
   Malformed `:SocialOutbox` nodes (only by manual edits) are logged at ERROR and left in Neo4j for repair.
4. Consumers must be idempotent and order-tolerant: keep the event with the newest `occurredAt` per
   (follower, followee) (Kafka order is best effort; correctness relies on `occurredAt`).

Blocks and mutes (stage 5A) use exactly the same mechanism, see "Blocks and mutes".

Follows that existed before stage 2A are backfilled automatically by `FollowProjectionBackfill` (see "Startup
backfills"): per follower, one Neo4j tx takes the same follower lock and per-follower clock and records one
`UserFollowedEvent` `:SocialOutbox` node per current FOLLOWS relationship; the forwarder does the rest. (A full
reconciliation would also emit `UserUnfollowedEvent` for pairs active in Feed's `follows` but absent from the
graph; not needed, the graph never lost relationships.)

## Startup backfills (`config/backfill`)

`StartupBackfillRunner` runs every `StartupBackfill` bean once per start, **asynchronously** after
`ApplicationReadyEvent` (all beans incl. `Neo4jSchemaInitializer` initialised; startup is never delayed), each on its own
daemon thread. A failing run (DB down) is retried every `app.backfill.retry-delay-ms` (30 s), resuming its progress. On
`ContextClosedEvent` the runner asks them to stop and waits up to 5 s (batches are milliseconds). `app.backfill.enabled:
false` disables the automatic run. Contract: durable completion marker (a completed backfill costs one lookup per
start), keyset progress persisted with each batch (resumable), safe on several instances and with live traffic.
Log: `Backfill <name> completed: N item(s), M record(s) written in X ms` (INFO); a completed one logs nothing above DEBUG.

| Backfill | Marker | Re-run (then restart) |
|---|---|---|
| `follows-projection-stage2a` (`FollowProjectionBackfill`) | Neo4j node `(:Backfill {name})` (unique constraint `backfill_name_unique`): `completed`, `cursor` (last follower userId), `followers`, `events` | `MATCH (m:Backfill {name: 'follows-projection-stage2a'}) DETACH DELETE m` |
| `post-text-index-v17` (`PostTextIndexBackfill`) | Postgres `backfill_state` row (V19): `completed_at`, `cursor_value` (last post id), `processed` | `UPDATE backfill_state SET completed_at = NULL, cursor_value = NULL WHERE name = 'post-text-index-v17'` (or DELETE the row) |

**Follows** (`follows-projection-stage2a`): keyset over follower `userId` (500 per read), one Neo4j tx per follower
(`FollowBackfillStore.emitCurrentFollows`): lock the marker (serialises instances; skip if complete or the cursor already
passed this follower), advance the cursor, lock the follower node exactly like `follow/unfollow`, read its current FOLLOWS
and take k ticks of `socialClock` (`base = max(now, clock + 1)`, events `base..base+k-1`). **Why no unfollow is ever
resurrected:** the event "active at tick t" is taken under the follower lock from the same clock as every live change of
that follower; an unfollow committed earlier removed the relationship (nothing emitted), one committed later gets a tick
> t and wins in `FollowProjection` whatever the Kafka order. Live follows in between are emitted by the live path. Lock
order is always marker -> follower (live: follower -> followee, never the marker): no deadlock. Re-running only
re-emits "active" for current relationships at newer ticks (harmless). A database without the FOLLOWS type completes
immediately.

**Hashtags/mentions** (`post-text-index-v17`): Java, not Flyway (`\p{L}`): keyset over `posts.id` of candidates (not
REPOST, no `post_hashtags`/`post_mentions` rows yet), 500 per tx. Each tx takes `pg_try_advisory_xact_lock(<"Soci">,
hashtext(name))` (other instance: waits 200 ms and continues from the shared cursor), locks the batch `FOR UPDATE SKIP
LOCKED` (never waits while holding locks: no deadlock with edits/deletes), re-checks "no rows" under the lock (an edit
that committed first wins), writes exactly what create/edit writes (`Post.hashtags()` via `PostHashtagRepository.replace`
incl. the `created_at` copy; mentions via `MentionLookup` + `MentionParser.resolve`, only against `author_profiles`, one
profile query per batch), and stores the cursor in the same tx. Rows skipped because someone held their lock are retried
one by one right after (single-row wait); the cursor stops before the first of them, so a crash loses nothing. Posts
without tags are passed once (the cursor), never rescanned. Note: a post created after 2B whose text has only unresolved
`@names` also has no rows, so it is re-resolved once (it may gain a mention of a user registered since).

## Kafka configuration rules

- All consumers must use `ErrorHandlingDeserializer` wrapping `JacksonJsonDeserializer` (Spring Kafka 4's Jackson 3
  successor of the deprecated `JsonDeserializer`, same `spring.json.*` properties):
  ```java
  props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
  props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
  ```
- Trusted packages: `com.socialapp.*`
- Every `@KafkaListener` names its `containerFactory` (from `KafkaConfig`) and `groupId`. `spring.kafka.consumer.*` in
  application.yml only configures Spring Boot's unused default factory; it is kept safe anyway (ErrorHandlingDeserializer
  -> JacksonJsonDeserializer, `DomainEvent` default type, no group id) and the default container factory gets the DLT error
  handler. `SocialAppIntegrationTest` asserts both.
- Producer: `ACKS_CONFIG = all`, `ENABLE_IDEMPOTENCE_CONFIG = true`; value serializer `KafkaConfig.eventSerializer()`
  (`JacksonJsonSerializer` on `DomainEventCodec.wireMapper()`, no type headers), also used for re-serialised dead letters
- Topics: `user-events`, `post-events`, `reaction-events`, `social-events`, `comment-events` (3 partitions,
  replication factor 1), each with a `<topic>.DLT` (DefaultErrorHandler: 3 attempts, then dead-letter)
- Consumer groups (one listener per BC per topic, switch on the sealed `DomainEvent`):
  `social-graph-group` (user-events), `feed-avatar-group` (user-events), `feed-sse-group` (post-events),
  `feed-follows-group` (social-events -> `follows`, `feed_blocks`, `feed_mutes`), `user-blocks-group` (UserManagement:
  social-events -> `user_blocks`, 5A), and Notification's `notification-users-group` (user-events ->
  actor profiles), `notification-social-group` (social-events, incl. the block/mute projection),
  `notification-posts-group` (post-events),
  `notification-reactions-group` (reaction-events), `notification-comments-group` (comment-events). New groups start
  at the earliest offset (history is replayed; see "Notifications" for why that creates no historic notifications).

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
- `V6`–`V9` — author avatar on posts (later dropped), `author_profiles`, comment reactions and replies
- `V10__author_profile_username.sql` — username + last_event_at in `author_profiles` (feed projection)
- `V11__drop_cross_bc_user_fks.sql` — removes FKs from feed tables to `users` (BC isolation)
- `V13__normalize_user_emails.sql` — lower-cases emails (V12 intentionally unused: do NOT add it, Flyway would reject it)
- `V14__outbox_events.sql` — transactional outbox table (see "Domain events / Outbox")
- `V15__feed_follows_projection.sql` — Feed's `follows` projection (tombstones, `last_event_at`, no FKs)
- `V16__posts_keyset_indexes.sql` — `posts (created_at DESC, id DESC)` and `(author_id, created_at DESC, id DESC)`
  for keyset pagination (replaces `idx_posts_author`)
- `V17__post_hashtags_and_mentions.sql` — `post_hashtags`, `post_mentions` (FK to posts ON DELETE CASCADE) and
  `author_profiles (lower(username))` index (see "Post text: limit, hashtags, mentions")
- `V18__post_types_repost_quote.sql` — `posts.type` (POST|REPOST|QUOTE, NOT NULL DEFAULT 'POST'), backfill of legacy
  shares, CHECKs `chk_posts_type` / `chk_posts_type_original`, partial unique index `ux_posts_repost_author_original`,
  `ix_posts_original_type` (replaces `idx_posts_original`) (see "Reposts and quotes")
- `V19__backfill_state.sql` — `backfill_state (name PK, cursor_value, processed, started_at, updated_at, completed_at)`:
  markers/progress of Java startup backfills (see "Startup backfills")
- `V20__notifications.sql` — Notification BC: `notifications` (dedupe key `ux_notifications_key (recipient_id, type,
  actor_id, target_id)`, tombstone `retracted`, `last_event_at`, CHECKs type / not-self, partial indexes for the page
  and the unread count), `notification_actor_profiles`, `notification_cutoff` (one row: migration time). No FKs.
- `V21__bookmarks.sql` — `bookmarks (user_id, post_id FK posts ON DELETE CASCADE, created_at; PK (user_id, post_id))`, index
  `ix_bookmarks_user_created_post (user_id, created_at DESC, post_id DESC)` (see "Bookmarks")
- `V22__post_media.sql` — `post_media (id PK, owner_id, post_id NULL FK posts ON DELETE CASCADE, position 0..3 NULL, content_type,
  width, height, size_bytes, storage_key UNIQUE, created_at)`, CHECKs (types, attached <=> post_id+position, sizes), partial
  unique `ux_post_media_post_position (post_id, position)`, partial `ix_post_media_unattached_owner (owner_id, created_at)` and
  `ix_post_media_unattached_created (created_at)` (both `WHERE post_id IS NULL`). No FK to users (see "Images in posts")
- `V23__posts_fulltext_search.sql` — GIN `ix_posts_content_fts` on `to_tsvector('simple', content)` (see "Search")
- `V24__users_username_trigram.sql` — `pg_trgm` extension + GIN `ix_users_username_trgm` on `lower(username)` (see "Search")
- `V25__post_hashtags_created_index.sql` — `ix_post_hashtags_created (created_at DESC)` for the trending window scan (see "Trending hashtags")
- `V26__feed_blocks_mutes.sql` — Feed's `feed_blocks (blocker_id, blocked_id, active, last_event_at)` (+ partial index
  `ix_feed_blocks_blocked_active (blocked_id, blocker_id) WHERE active`) and `feed_mutes (muter_id, muted_id, ...)`
- `V27__notification_blocks_mutes.sql` — `notification_blocks`, `notification_mutes` (same shape, PK lookups only)
- `V28__user_blocks.sql` — UserManagement's `user_blocks` (+ `ix_user_blocks_blocked_active`) for people search
  (all three: tombstones, newest `occurredAt` wins, no FKs; see "Blocks and mutes")

**Always create a new numbered migration file. Never modify existing ones.**

## API error text language

API error messages (incl. Bean Validation, e.g. `Validation failed: content must not be blank`) are deterministic
**English**, whatever the client's `Accept-Language` or the JVM/OS locale (`-Duser.language=pl` would otherwise give
Hibernate Validator's Polish "nie może być odstępem"). Two layers: `spring.web.locale: en` + `spring.web.locale-resolver:
fixed` (request locale), and `config/ValidationConfig` (replaces Boot's `defaultValidator`; interpolates in
`Locale.ENGLISH` regardless of the requested locale, so validation outside a request is English too). Do not localise
API messages; tests: `ValidationConfigTest`, `PostControllerSecurityTest`, `SocialAppIntegrationTest` (Accept-Language pl
on a pl JVM default).

## Password hashing

Uses BCrypt (`BCryptPasswordEncoder`). Do NOT change to SHA-512 — BCrypt is intentionally slow and salted. SHA-512 is inappropriate for passwords.

## Feed timeline (REST)

`GET /api/v1/feed?scope=all|following&cursor=<opaque>&size=20` -> `{"items": [PostResponse...], "nextCursor": "<opaque>" | null}`
(frontend contract; the old `page` param is gone and ignored).
- `scope`: `all` (default, every post) | `following` (posts of users the caller follows per Feed's `follows`
  projection, plus the caller's own); other values -> 400.
- Keyset pagination over `(created_at DESC, id DESC)` (`TimelineService`, native row-value queries in
  `PostRepository`); fetches size+1 to detect the last page (`nextCursor: null`). New/deleted posts between
  requests never duplicate or skip the remaining posts. Posts hidden from the caller by blocks/mutes are filtered in
  the same SQL (see "Blocks and mutes").
- `cursor`: unpadded base64url of `v1|<ISO instant>|<uuid>` (`FeedCursor`); malformed -> 400 JSON.
- `size`: default 20, values > 100 clamped to 100, < 1 or non-numeric -> 400 JSON.

`follows` projection (`FollowProjection`): one atomic upsert per event that applies only if `occurredAt` is newer
than `last_event_at`; unfollow leaves a tombstone (`active = false`), so late/redelivered/out-of-order events
never resurrect or delete wrongly.

## Post text: limit, hashtags, mentions

**Limit:** a post's own text (`content`; for a QUOTE, its required `comment`) is at most `Post.MAX_CONTENT_LENGTH`
= **500 Unicode code points** (emoji = 1; whitespace/newlines count; no trimming; grapheme clusters such as ZWJ
emoji count per code point). Enforced in the aggregate (`Post.create/quote/edit` -> `IllegalArgumentException`
-> 400) and on the request records with `@MaxCodePoints(Post.MAX_CONTENT_LENGTH)` (NOT `@Size`: it counts UTF-16
units and would reject 300 emoji). Blank content and blank quote comments are rejected (a REPOST has no text). Posts stored
before the limit stay readable; editing one requires shortening it. Comments keep their own 2000 limit.

**Hashtags** (`HashtagParser`, mirrored 1:1 by the frontend - change both or neither):
- `#` + maximal run of `[\p{L}\p{N}_]` of 1..50 code points (a longer run is no tag at all, not a prefix);
- the `#` is at text start or preceded by a char NOT in `[\p{L}\p{N}_&/]` (`a#b`, `&#123;`, `http://x/#anchor`
  and the second tag of `#a#b` are not tags);
- at least one letter (`#123` is not a tag);
- normalised: `toLowerCase(Locale.ROOT)`, no `#`, de-duplicated, order of first appearance, max 30 per post.
- Implemented as a code-point scanner: Java regex lookbehind misses a supplementary-plane letter before `#`.
  Known limitation of the rule: combining marks (`\p{M}`, NFD accents, many Indic vowel signs) end a tag.

**Mentions** (`MentionParser`): `@` + maximal run of `[\p{L}\p{N}_.-]`, trailing `.`/`-` trimmed, 3..30 code points;
the `@` at text start or preceded by a char NOT in `[\p{L}\p{N}_.@-]` (e-mails never match). Resolved ONLY against
Feed's `author_profiles` (one batched `lower(username) IN (...)` query per save, re-checked in Java with
`Locale.ROOT`): case-insensitive; an exact-case match wins if usernames differ only in case, otherwise ambiguous ->
skipped; unknown -> plain text; self-mention not stored; max 30 resolved per post.

**Persistence:** `PostService.saveWithTextIndex` saves + flushes the post, then replaces its rows in `post_hashtags
(post_id, tag, position, created_at)` and `post_mentions (post_id, mentioned_user_id, username, position)` in the
same transaction (create, edit, quote, repost; edit locks the post row). `post_hashtags.created_at` is copied from the
post row by the INSERT, so the tag timeline pages on index `(tag, created_at DESC, post_id DESC)`.
`post_mentions.username` is the canonical name at save time and is NOT refreshed on rename (so `@name` in the text
keeps matching; `mentioned_user_id` is authoritative). Posts created before V17 get their rows from the one-time
`post-text-index-v17` startup backfill (see "Startup backfills"), with the same parsers and resolution.

**Reads:** every `PostResponse` (feed pages, tag pages, GET/create/edit/repost/quote/share/like responses) has
`hashtags: string[]` and `mentions: [{userId, username}]` for the post's own text (never `originalContent`; a
quote's comment only), batch-loaded by `PostService.views` (see "Reposts and quotes" for the full field list and
query budget: no N+1).

`GET /api/v1/hashtags/{tag}/posts?cursor=&size=20` -> `{"items": [PostResponse...], "nextCursor": ...}`: same
keyset ordering, cursor codec and size rules as `/feed`. QUOTEs with the tag in their comment appear; REPOSTs never
do (no text, and the query also filters `type <> 'REPOST'`). `{tag}` is case-insensitive, one leading `#` (`%23`)
tolerated; invalid tag -> 400 JSON; unknown tag -> 200 with empty items.

## Reposts and quotes (stage 2C)

`posts.type` (`PostType`): **POST** (own text) | **REPOST** (`content = ""`, no hashtags/mentions, no own image) |
**QUOTE** (required comment, 1..500 code points, hashtags/mentions indexed like a post). A REPOST/QUOTE points at its
original via `original_post_id`; the type is immutable. Invariants live in `Post.repost/quote/edit` and are mirrored
by V18 CHECKs (POST has no original; REPOST has one and `content = ''`).

- **Targets / flattening:** reposting or quoting a REPOST item targets that repost's original
  (`Post.repostTargetId()`); a QUOTE or a POST is targeted itself. A repost never points at a repost (the factories
  reject it). Unknown/deleted target -> 404. Own posts may be reposted/quoted.
- **Idempotent repost:** at most one REPOST per (author, original): partial unique index
  `ux_posts_repost_author_original (author_id, original_post_id) WHERE type = 'REPOST'`. `PostService.repost` reads
  the item without a lock, then **locks the original's row** (`findByIdForUpdate`, same approach as reactions), so a
  concurrent double submit is serialised and the second call finds the first one's row (200, not 500). QUOTEs are
  unlimited (they lock the original too, so they cannot race its deletion).
- **Undo:** `PostService.undoRepost` resolves a repost item id to its original, locks the original, deletes the
  caller's REPOST; no repost / unknown id -> no-op (204).
- **Edit:** a REPOST cannot be edited (`ConflictException` -> 409; ownership is checked first -> 403 for others). A
  QUOTE's comment is edited through `PUT /posts/{id}` (500 limit, re-indexes hashtags/mentions).
- **Deleting an original** (`PostService.deletePost`, row locked first): its REPOSTs are deleted (bulk delete; their
  comments/reactions/index rows cascade by FK), its QUOTEs get `original_content` / `original_author_username`
  cleared, then the V3 FK (`ON DELETE SET NULL`) nulls their `original_post_id`: they stay, with `original = null`
  ("unavailable"). The CHECK forbids a REPOST without original, so a raw `DELETE` of an original that still has
  reposts fails instead of orphaning them - delete its reposts first (or go through the service).
- **Lock order** (deadlock-free): original -> its reposts/quotes. Never lock a repost/quote row and then its
  original (that is why `lockTarget` reads the item without a lock).
- **Events:** reposts/quotes are published like posts (`PostCreatedEvent` via the outbox, so they appear live in For
  you, and in Following when the reposter is followed). `PostCreatedEvent` gained `postType` ("POST"|"REPOST"|
  "QUOTE") and `originalPostId`; the first seven fields are unchanged and events without the new fields
  deserialize as a plain post.

**Read model** (`PostService.views(posts, viewerId)` -> `PostView` -> `PostResponse`), on every endpoint returning
posts: `type`, `repostsCount` / `quotesCount` (REPOST / QUOTE rows pointing at the post: ONE grouped query on
`ix_posts_original_type`, not denormalised columns), `viewerReposted` (caller has a REPOST of it: one IN query on the
partial unique index), `userReaction` (caller's LIKE/DISLIKE or null: one IN query), `originalAuthorId`, and
`original: PostResponse | null` - the full original (own avatar, hashtags, mentions, counters, caller's state) for a
REPOST/QUOTE, null for a POST or when the original is gone. One level only (the nested one has `original = null`),
**except a REPOST of a QUOTE**: its nested quote carries the quoted post (second level, `original = null` there; null
if the quoted post is gone). Flat legacy fields `originalPostId`, `originalAuthorUsername`, `originalContent` are
kept (snapshot at creation). For a REPOST item, top-level `content` is "" and its counters/reactions are those of the
repost row itself (clients act on `original`). Query budget per page, independent of its size: <= 2 for the originals
(level 1, then level 2) + avatars + hashtags + mentions + media + counts + (with a viewer) reposted flags + bookmarks +
reactions = <= 10. `media` (3C) see "Images in posts".
Timelines (`all`, `following`) contain REPOST and QUOTE items ordered by their own `created_at` like any post.

Endpoints:
- `POST /api/v1/posts/{id}/repost` (no body) -> 201 PostResponse (type REPOST) | 200 if the caller already reposted it.
- `DELETE /api/v1/posts/{id}/repost` -> 204 always ({id} = original id or a repost item id).
- `POST /api/v1/posts/{id}/quote` `{ "comment": string, "mediaIds"?: uuid[] }` -> 201 PostResponse (type QUOTE);
  blank/missing/> 500 -> 400 (`mediaIds`: see "Images in posts").
- `POST /api/v1/posts/{id}/share` `{ "comment"?: string }` - **DEPRECATED alias** (kept so older clients work): blank or
  missing comment -> repost semantics (201/200), otherwise quote (201). Use `/repost` or `/quote`.

**V18 backfill** of legacy shares (rows with any `original_*` column set): blank content -> REPOST (content ''),
otherwise QUOTE; shares of a share are flattened to the end of the chain (snapshot refreshed); shares whose original
is gone: REPOSTs deleted, QUOTEs kept with original/snapshot NULL; several REPOSTs by one author of the same original:
the oldest `(created_at, id)` is kept, the others deleted (with their comments/reactions); `image_url` of REPOSTs and
QUOTEs cleared (a share only ever copied the original's image). Tested in `V18BackfillMigrationTest`.

## Bookmarks (stage 3B)

Private "saved posts" in Feed (Postgres/JPA); no Kafka events, no other BC involved.
- `PUT /posts/{id}/bookmark` -> 204, idempotent (`INSERT ... SELECT FROM posts ... ON CONFLICT DO NOTHING`, race-safe);
  unknown/deleted post -> 404. `DELETE /posts/{id}/bookmark` -> 204 always. A repost item is bookmarked as given (its own
  id, not its original). Everything is scoped by the caller's id from the token; there is no way to address another
  user's bookmarks.
- `GET /bookmarks?cursor=&size=` -> same `{items, nextCursor}` envelope and `PostResponse` as `/feed` (embedded originals,
  counters, viewer flags). Keyset over `(bookmarks.created_at DESC, post_id DESC)`; the cursor is a `FeedCursor` holding the
  BOOKMARK time. Size default 20, clamp 100, `< 1` or a malformed cursor -> 400. Two queries (bookmarks, their posts); a post
  missing in between is skipped. Re-bookmarking keeps the original position.
- `V21` FK `ON DELETE CASCADE`: deleting a post (also a repost deleted with its original) removes everybody's bookmarks of it.
  `user_id` has no FK (other BC).
- `PostResponse.bookmarked` (all Post DTOs, nested originals included): the viewer bookmarked THAT item; ONE batch query
  (`BookmarkRepository.findBookmarkedPostIds`) in `PostService.views`. SSE broadcasts are viewer-agnostic: the embedded
  `original` has `bookmarked=false` and the payload carries no flag (clients treat live posts as not bookmarked).
- Tests: `BookmarkServiceTest`, `BookmarkControllerTest` (web), `BookmarkPostgresTest`, `BookmarkIntegrationTest`.

## Images in posts (stage 3C)

Feed only (Postgres/JPA + files behind the `MediaStorage` port; today `LocalDiskMediaStorage`, an S3/MinIO adapter can
replace it: store/delete/publicUrl). No Kafka event changes; the SSE payload carries `media`.

**Upload** `POST /api/v1/media` (auth, multipart field `file`) -> 201 `{id, url, width, height}`, stored UNATTACHED and owned
by the caller. Errors are JSON `{error, timestamp}`: missing/empty file or not multipart 400; > 5 MB (`app.media.max-bytes`,
or the multipart limit) or > 8000 px per side / > 50 MP / too many frames 413; not an accepted image 415; > 20 pending
unattached uploads per user 429 (`app.media.max-pending-per-user`, counted under a per-user advisory lock, so concurrent
uploads cannot exceed it); all decode slots busy for 30 s 503; no token 401. Order: validate bytes (no tx) -> commit the row ->
write the file (on failure the row is deleted, 500). A crash in between leaves only a row without a file, purged later.

**Validation** (`ImageInspector`, pure JDK; client Content-Type and file name are never read): magic bytes pick JPEG/PNG/GIF/WebP
(SVG, HTML, PDF, BMP, TIFF, HEIC... -> 415); whole-file scan for HTML/script markers (`<script`, `<iframe`, `<html `...) -> 415;
structural walk to the end marker with bounds and PNG CRC checks (PNG/GIF/WebP: trailing data -> 415; JPEG: data after EOI,
e.g. motion-photo videos / Ultra HDR gain maps, is cut off); limits from the headers before decoding (decompression bombs);
first frame decoded by ImageIO with subsampling (max 2 concurrent). WebP has no JDK decoder: structural validation only.
**Metadata is stripped** structurally (no re-encode): JPEG APP1 EXIF/XMP (GPS!), APP13, COM, MPF (EXIF orientation is kept as a
minimal EXIF segment and applied to the reported width/height), PNG tEXt/zTXt/iTXt/eXIf/tIME, GIF comment/plain-text/app
extensions except the loop, WebP EXIF/XMP. ICC profiles are kept.

**Storage / serving:** file name = server-generated key `<random uuid>.<ext>` (ext from the detected format; unrelated to the
media id) in `app.media.dir` (default `./uploads/media`, git-ignored via `uploads/`). `GET /uploads/media/{key}` (public, like
avatars) is served by `MediaFileController`, not a static handler: only keys matching the exact shape are looked up (no
traversal), Content-Type from the extension, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none';
sandbox`, `Cache-Control: public, max-age=86400`. URL = `app.base-url` + `/uploads/media/<key>` (absolute, like avatar URLs;
the frontend dev server loads it directly from :8080, `<img>` needs no CORS/proxy). `WebConfig` now maps only
`/uploads/avatars/**` (from `app.upload.dir`) statically.

**Attach:** `POST /posts` and `POST /posts/{id}/quote` take optional `mediaIds: uuid[]` (max 4 = `PostMedia.MAX_PER_POST`, no
duplicates, else 400). `PostService` locks the uploads (`SELECT ... FOR UPDATE`, id order) before saving the post and attaches
them (post_id + position = request order) in the same transaction: unknown or someone else's upload -> 404 `Media not found`
(indistinguishable, nothing leaks), already attached -> 400; any failure rolls back the post. Content stays required (no
media-only posts); reposts have no media; edits never change media. Concurrent attaches of one upload: exactly one wins.

**Read:** `PostResponse.media` (every endpoint, nested `original` included; SSE `new-post` payload `media` and its `original`)
= `[{id, url, width, height}]` ordered by position, `[]` when none. ONE query per page for the posts and their originals
(`PostMediaRepository.findByPostIds`) in `PostService.views`; URLs are built from rows only, so a missing file never breaks
a list (the browser gets a 404 for that image).

**Delete:** deleting a post cascades its `post_media` rows (FK); `PostService.deletePost` deletes the files after commit
(best effort, logged, never fails the delete; a rollback keeps them). QUOTEs of a deleted original keep their own media.
**Cleanup:** `MediaService.purgeExpired` deletes unattached uploads older than `app.media.unattached-ttl` (24h) in batches
(`FOR UPDATE SKIP LOCKED`, so an upload being attached is never deleted), then their files. Scheduled by `MediaCleanupJob`
every `cleanup-interval-ms` (first run after the same delay), off with `app.media.cleanup-enabled: false` (set in all
integration tests); stops between batches on shutdown.

Config (`app.media.*`): `dir` (./uploads/media), `max-bytes` (5242880), `max-pending-per-user` (20), `unattached-ttl` (24h),
`cleanup-enabled` (true), `cleanup-interval-ms` (3600000), `cleanup-batch-size` (500). `spring.servlet.multipart.max-file-size`
is 5MB / `max-request-size` 6MB globally; the avatar endpoint keeps its own 2 MB check. Multipart limit errors -> 413 JSON,
non-multipart -> 400 JSON, malformed JSON bodies -> 400 JSON (`GlobalExceptionHandler`).
Known limits: no rate limit beyond the pending cap; WebP not decoded; files of a failed delete stay (logged); deleted posts'
images may stay in browser/proxy caches up to a day.
Tests: `ImageInspectorTest`, `LocalDiskMediaStorageTest`, `MediaControllerTest` (web), `PostControllerSecurityTest` (mediaIds),
`PostMediaPostgresTest` (attach/order/concurrency/cascade/cleanup/N+1), `PostEventConsumerTest` (SSE), `MediaIntegrationTest`.

## SSE (Server-Sent Events)

`GET /api/v1/feed/stream?access_token=...&scope=all|following` (default `all`).
`FeedSseService` keeps one subscription (emitter, viewer, scope) per stream. On `broadcast()`:
- `scope=following` subscriptions only get posts whose author is the viewer or followed by the viewer,
  checked per post via `FolloweeLookup` (viewer's followee set cached for `app.feed.followees-cache-ttl`,
  default 5s, invalidated when this instance applies a follow event) - follows made while connected apply
  without reconnecting
- Iterates all admitted emitters
- Sends event with `EventBuilder.event("new-post")`; payload `PostSsePayload` includes `hashtags` and `mentions`
  (same shapes as `PostResponse`, read from Feed's tables by `PostEventConsumer`, never null; since 3C also `media`), and since 2C `type`,
  `originalAuthorId` and `original` (a `PostResponse` built by `PostService.views` without a viewer: same nesting
  rules as REST, `viewerReposted = false`, `userReaction = null`). Pre-2C field names are unchanged (`postId`).
  The following-scope filter uses the payload's `authorId` (the reposter/quoter).
- Both scopes (5A): per-viewer block/mute filter via `BlockMuteLookup`: skip, full payload, or a variant without the
  blocked embedded post; each variant is serialised once per broadcast (see "Blocks and mutes").
- Removes dead emitters on `SseEmitter.SseEventBuilderException`

Heartbeat emitter is sent on subscribe with event name `"connected"`. Do NOT change event names — frontend listens for exactly `"connected"` and `"new-post"`.
The notification stream (`/api/v1/notifications/stream`, see "Notifications") uses `"connected"` and `"notification"`.

## Notifications (stage 3A)

Notification BC learns everything from Kafka only (no import of other BCs, no FKs); PostgreSQL (JPA writes, JDBC reads).

**Types and rules** (`NotificationPolicy`, pure): FOLLOW (someone follows you), LIKE (your post or comment; dislikes
never notify), COMMENT (on your post), REPLY (to your comment), MENTION (`@you` in a post or a quote's comment), REPOST,
QUOTE (of your post). Never yourself. One action notifies a recipient at most once, precedence REPLY > COMMENT and
QUOTE/REPOST > MENTION (a reply to your comment on your own post is only a REPLY; a quote that mentions the quoted
author is only a QUOTE). A reply in your post's thread to SOMEONE ELSE's comment gives you a COMMENT.

**Dedupe target** (`target_id`): FOLLOW = followee, LIKE = post or comment, COMMENT/REPLY = the new comment, MENTION =
the mentioning post, REPOST = the ORIGINAL (undo + repost again never notifies twice), QUOTE = the quote post (each quote
notifies). `postId` = the post to open (COMMENT/REPLY/LIKE-on-comment: the comment's post; REPOST: the original;
QUOTE/MENTION: the new post; FOLLOW: null). Snippet (`Snippet.of`, <= 120 code points, whitespace collapsed): the comment
(COMMENT/REPLY), the liked text (LIKE), the post text (MENTION), the quote's comment (QUOTE), the reposted text (REPOST),
null for FOLLOW. Snapshots: stay when the target is edited/deleted (no event exists for those).

**Idempotency / toggling** (`Notification.apply`, row locked by key; first insert `ON CONFLICT DO NOTHING`):
newest `occurredAt` wins (`last_event_at`, micros), so redeliveries and out-of-order events are no-ops. A retraction
(unlike, unfollow) hides the notification only while UNREAD (tombstone `retracted`); a READ one stays (the user saw it;
the list does not change under them) and re-liking never notifies again. Re-liking after an unread retraction
resurrects the SAME row (same id, new `createdAt`, unread) and pushes it again over SSE: toggling yields at most one
unread notification. An unlike seen before its like leaves a tombstone, so the older like cannot resurrect it. Undo
repost and comment/post deletion publish nothing, so they never retract.

**No historic notifications:** V20 stores `notification_cutoff.since` = migration time; events with an older
`occurredAt` are ignored (the new consumer groups replay the retained Kafka history from the earliest offset). No backfill
of past follows/likes/comments. The actor profile projection (`notification_actor_profiles`, same rules as Feed's
`AuthorProfileProjection`: create-if-absent, newest wins; events without userId dropped with a WARN, never DLT'd) DOES use
the history, so existing users get names as long as their user events are still retained in Kafka (7 days by default).
`actorUsername` is resolved at read time: profile username, else the name carried by the event (post/comment events),
else `"unknown"`; it refreshes as soon as the profile arrives.

**API** (all authenticated, always the caller's own notifications):
- `GET /api/v1/notifications?cursor=&size=20` -> `{"items": [Notification...], "nextCursor": string|null}`, keyset
  `(created_at DESC, id DESC)` (`NotificationCursor`, same opaque `v1|instant|uuid` format as the feed), size default 20,
  clamp 100, < 1 / non-numeric / malformed cursor -> 400 JSON. One query per page (LEFT JOIN profiles).
- Notification = `{id, type, actorId, actorUsername, actorAvatarUrl, postId, commentId, snippet, read, createdAt}`.
- `GET /api/v1/notifications/unread-count` -> `{"count": n}` (partial index).
- `POST /api/v1/notifications/read` `{"ids": [...]}` -> 204; missing body / missing or empty `ids` = mark ALL read;
  ids of other users or unknown ids are silently ignored; idempotent; > 1000 ids or a non-uuid -> 400.
- SSE `GET /api/v1/notifications/stream?access_token=<jwt>`: `connected` (data `ok`), then `notification` (data =
  Notification JSON) for the caller only, to all of the caller's open streams. Pushed after the consumer's transaction
  committed (`@Async`); a retraction is not pushed (clients see it on the next list refresh). Like the feed stream, live
  delivery only reaches streams on the instance that consumed the event (single instance today). Streams are completed
  on `ContextClosedEvent` (graceful shutdown stays ~3 s).

## Authentication

Stateless JWT (Spring Security + jjwt, HS256). `config/security/SecurityConfig` holds the filter chain and CORS;
`usermanagement/infrastructure/security/JwtService` issues/verifies tokens (`sub` = user UUID, `username` claim).
Config: `app.jwt.secret` (env `APP_JWT_SECRET`, >= 32 bytes) and `app.jwt.expiration-minutes`.

- Public: `POST /api/v1/users`, `POST /api/v1/users/login` (both return user fields + `token`), `GET /uploads/**` (avatars,
  post images), CORS preflight.
- Everything else needs `Authorization: Bearer <token>`; the SSE streams `GET /api/v1/feed/stream` and
  `GET /api/v1/notifications/stream` (only these two paths, GET only) also accept `?access_token=`
  (`JwtAuthenticationFilter`).
- Never take the acting user from request params/bodies. Controllers read it via
  `@AuthenticationPrincipal AuthenticatedUser me` (`shared/security`, no cross-BC import).
- Ownership checks live in the aggregates (`Post/Comment.ensureAuthoredBy`) and throw
  `shared.domain.ForbiddenOperationException` -> 403. `SecurityException` -> 401 is only for bad login credentials.

## Tests

- `SocialAppIntegrationTest` (Testcontainers: Postgres, Neo4j, Kafka): `@Testcontainers` is declared BEFORE
  `@SpringBootTest` and the class has `@DirtiesContext(AFTER_CLASS)`, so the context (relay, forwarder, listeners) is
  closed before the containers stop (JUnit runs after-all callbacks in reverse registration order). Keep that order, or
  the log ends with connection errors such as "Outbox relay run failed". It binds the server to 127.0.0.1 so Tomcat's
  graceful shutdown does not hit a blocked acceptor on sandboxed machines.
- `NotificationIntegrationTest` (same container/context lifecycle): follow/like/unlike/comment/reply/mention/repost/quote
  over HTTP -> outbox -> Kafka -> Notification -> REST + SSE (isolation between users), and the enriched outbox rows.
- `BookmarkIntegrationTest` (same lifecycle): bookmark/list/page/flag/isolation/cascade over HTTP.
- `BlockMuteIntegrationTest` (same lifecycle): block/mute over HTTP -> Neo4j Cypher -> outbox -> Kafka -> all three
  projections -> every filtered view, unblock/unmute. Waits for projections by polling the observable effect, and retries
  the first follow while a user has not reached Neo4j yet (never sleeps).
- `MediaIntegrationTest` (same lifecycle): upload (real Tomcat multipart limits) -> attach -> feed/post/SSE media -> serving
  headers -> delete removes rows and files; purge of expired uploads.
- Kafka error handling is tested end to end for both a malformed record (DeserializationException -> DLT at once) and a
  record that deserializes but keeps failing in the listener (3 attempts, then `<topic>.DLT` with `kafka_dlt-*` headers;
  later records of the same partition still processed).

## Build

```bash
./mvnw spring-boot:run          # run with hot reload
./mvnw clean package -DskipTests # build jar
./mvnw test                     # run tests
```

Do NOT use `mvn` command directly — use the Maven wrapper `./mvnw`.

## Spring Boot 4 migration notes (3.5 -> 4.0)

- **Modular starters.** Auto-configuration is split per technology: `spring-boot-starter-flyway` and
  `spring-boot-starter-kafka` are required (with only `flyway-core` / `spring-kafka`, Flyway would silently not run and
  KafkaAdmin / Boot's default Kafka factories would not exist). Web is `spring-boot-starter-webmvc` (was `-web`), JSON
  is `spring-boot-starter-jackson`. `UserDetailsServiceAutoConfiguration` moved to
  `org.springframework.boot.security.autoconfigure`.
- **Test starters and annotation packages.** `spring-boot-starter-webmvc-test` (`@WebMvcTest`:
  `org.springframework.boot.webmvc.test.autoconfigure`), `spring-boot-starter-data-jpa-test` (`@DataJpaTest`:
  `org.springframework.boot.data.jpa.test.autoconfigure`, `@AutoConfigureTestDatabase`:
  `org.springframework.boot.jdbc.test.autoconfigure`, `TestEntityManager`: `org.springframework.boot.jpa.test.autoconfigure`),
  `spring-boot-starter-security-test`. `@MockBean`/`@SpyBean` no longer exist: use `@MockitoBean`/`@MockitoSpyBean`.
- **Jackson 3** (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`) in all production code. Boot's
  bean is a `JsonMapper`; there is no Jackson 2 `ObjectMapper` bean. Jackson 3 exceptions are unchecked
  (`JacksonException`). Wire formats are unchanged and must stay so:
  - REST/SSE: `spring.jackson.use-jackson2-defaults: true` (declaration order instead of Jackson 3's alphabetical
    order, ISO dates, lenient reads) + `spring.jackson.json.write.combine-unicode-surrogates-in-utf8: false` (emoji
    escaped in response bodies as before).
  - Events (outbox payload, Kafka values, re-serialised dead letters): `DomainEventCodec.wireMapper()` (Jackson 3
    `builderWithJackson2Defaults`), so `occurredAt` stays a decimal epoch-seconds number. `DomainEventWireFormatTest` pins
    the bytes captured from the Boot 3.5 build. Never produce events with Boot's mapper or Spring Kafka's
    `JacksonMapperUtils.enhancedJsonMapper()` (ISO dates, alphabetical order). Consumers use `JacksonJsonDeserializer`'s
    default mapper (reads both date forms, ignores unknown properties).
  - Spring Kafka's Jackson 2 `JsonSerializer`/`JsonDeserializer`/`JacksonUtils` are deprecated for removal; do not use
    them. Jackson 2 is still on the class path, only transitively (Flyway, jjwt-jackson); some tests parse responses
    with it as an independent reader.
- **Testcontainers 2.0.x**, version managed by Boot (no own property/BOM): artifacts `testcontainers-junit-jupiter`,
  `-postgresql`, `-neo4j`, `-kafka`; classes `org.testcontainers.postgresql.PostgreSQLContainer`,
  `org.testcontainers.neo4j.Neo4jContainer` (no type parameter) and, for the `confluentinc/cp-kafka` image,
  `org.testcontainers.kafka.ConfluentKafkaContainer` (the old `org.testcontainers.containers.*` classes are deprecated).
- **Lombok on JDK 25:** javac 23+ runs only declared annotation processors, so `maven-compiler-plugin` lists Lombok in
  `annotationProcessorPaths` (`${lombok.version}`, managed by Boot). Without it a clean build fails.
- Left as is (deprecated, still working): `HttpStatus.PAYLOAD_TOO_LARGE` (Framework 7 name: `CONTENT_TOO_LARGE`, 413).

## Who to follow (stage 4A)

`GET /api/v1/social/suggestions?limit=5` (caller = JWT principal) -> `[{userId, username, avatarUrl, mutualFollowers}]`.
`SocialGraphService.getSuggestions` (read-only Neo4j tx, limit clamped 1..20): friends of friends ranked by number of
mutual followees (ties: username, id), topped up with the most followed users (`mutualFollowers = 0`, cold start / small
graphs). Never contains the viewer, anyone already followed, anyone the viewer blocks or mutes, or anyone who blocks the
viewer (5A). Cypher lives in `Neo4jFollowSuggestionQueries`
(port `FollowSuggestionQueries`, `Neo4jClient`); the popularity fallback scans all `:User` nodes - revisit (cached
follower counts) if the graph gets large. Tests: `SocialGraphServiceTest` (fill/clamp), `SocialAppIntegrationTest` order 23.

## Search (stage 4B)

Both searches are plain Postgres, one per owning BC (no cross-BC import, no Kafka).

**Posts** `GET /api/v1/search/posts?q=&cursor=&size=20` (Feed, `PostController`) -> same `{items, nextCursor}` envelope, keyset
cursor and size rules as `/feed` (`TimelineService.searchPage`, `PostRepository.findSearchPageAfter`). Newest matches first
(NOT relevance-ranked: keeps the opaque `FeedCursor` and stable paging). Match = `to_tsvector('simple', content) @@
websearch_to_tsquery('simple', :q)`; the query must use exactly that expression to hit `ix_posts_content_fts` (V23). `simple`
= no stemming/stop words/accent folding (works the same for PL and EN, but "kot" does not match "kotem"). `websearch_to_tsquery`
never raises on user input (`!!!`, quotes, parentheses -> no rows): words are ANDed, `"phrase"`, `or`, `-word` work, `#kafka`
matches the word kafka. Searches a post's own text (QUOTE: its comment); REPOSTs have `content = ''` so never match (query also
filters `type <> 'REPOST'`); comments and `original_content` are not searched. `q` trimmed, 1..100 code points, else 400
(also missing `q`); `size` < 1 -> 400.

**People** `GET /api/v1/search/users?q=&limit=10` (UserManagement, `UserSearchController`, `UserService.searchByUsername`) ->
`[{id, username, bio, avatarUrl}]` (public fields only - never e-mail). Case-insensitive substring of `username`; ranked exact
match, then prefix, then the rest by username; `limit` clamped 1..20, no cursor. `q` trimmed, 1..50 code points, else 400. LIKE
wildcards in `q` are literal (escaped in `UserPersistenceAdapter`). Users the caller blocks or is blocked by are left out
(UserManagement's `user_blocks` projection, 5A). Index: trigram GIN (V24) serves terms of >= 3 characters;
shorter ones scan the users table. `CREATE EXTENSION pg_trgm` needs only CREATE on the database (trusted extension, PG 13+).
Tests: `TimelinePostgresTest` (search section), `UserSearchPostgresTest`, `UserServiceTest`, e2e `search.spec.ts`.

## Trending hashtags (stage 4C)

`GET /api/v1/trends/hashtags?hours=24&limit=10` (Feed, `TrendsController` -> `TrendsService` -> `PostHashtagRepository.trending`)
-> `[{tag, posts, authors}]` (tag normalised, no `#`). `hours` clamped 1..168, `limit` clamped 1..20 (never 400); nothing in the
window -> 200 `[]`. ONE aggregate over `post_hashtags JOIN posts` for rows with `created_at >= now - hours` (`ix_post_hashtags_created`,
V25), ranked by DISTINCT AUTHORS, then posts, then tag - so one account repeating a tag cannot trend it. A post counts once per
tag however often the text repeats it (the index is de-duplicated). Counts POSTs and QUOTEs (own text), never REPOSTs. Computed per
request, no cache (a fresh post shows at once); add a short TTL cache here if the feed's request volume ever makes it matter.
Not "velocity": a tag is ranked by volume in the window, not by growth. Since 5A the counts are per viewer: posts of users
the caller blocks, is blocked by or mutes are not counted (so a shared cache would have to be keyed by viewer). Tests: `TrendsServiceTest`, `TrendsPostgresTest`, e2e `trends.spec.ts`.

## Blocks and mutes (stage 5A)

**Block** (mutual in effect): A blocks B -> the FOLLOWS relationships A->B and B->A are deleted, new follows in either
direction are refused (403 `You cannot follow this user`, same text for both sides), each side's content is hidden from the
other (table below), no notifications are created between them and existing ones are hidden. Unblock restores visibility
(nothing but the follows was deleted; follows are NOT restored). **Mute** (one-directional, silent): A mutes B -> B's
posts/reposts and notifications are hidden from A's lists only; follows untouched; B is not told and can still interact;
B's content stays visible when explicitly opened. Unmute restores.

**Source of truth: SocialGraph (Neo4j)**, like FOLLOWS: `(blocker)-[:BLOCKS {since}]->(blocked)`, `(muter)-[:MUTES {since}]->(muted)`.
Writes are single Cypher statements in `UserNodeRepository` on the follow/unfollow template (acting node write-locked, change +
`:SocialOutbox` node(s) in one Neo4j tx, `occurredAt` from the per-user `socialClock`, a no-op records nothing). `block` locks
BOTH nodes in `userId` order (crossing blocks cannot deadlock; follow/unfollow lock their follower, so they serialise with the
block on that node), deletes both FOLLOWS directions and records up to three events: `UserUnfollowedEvent(blocker, blocked)`
(blocker's clock, tick t), `UserUnfollowedEvent(blocked, blocker)` (the blocked user's own clock: follow consumers order per
follower) and `UserBlockedEvent` (blocker's clock, t+1). `follow` adds `NOT EXISTS { (a)-[:BLOCKS]-(b) }`; when it matched
nothing, `SocialGraphService.follow` checks the block in the same tx (lock still held) and throws `ForbiddenOperationException`.
New `:SocialOutbox` nodes name the pair `sourceId`/`targetId` (follow events keep `followerId`/`followeeId`; `SocialOutboxStore`
reads both). Block/mute: self 400, unknown user 404. Unblock/unmute never validate and are always 204.
Why not Postgres as the source of truth: the block must be checked atomically with follows (Neo4j) and "who to follow" needs it
in the graph; per-BC projections keep the other BCs independent.

**Events** (`social-events`, append-only, pinned in `DomainEventWireFormatTest`): `UserBlockedEvent` / `UserUnblockedEvent`
(eventId, occurredAt, blockerId, blockedId; key = blockerId, aggregateType `Block`), `UserMutedEvent` / `UserUnmutedEvent`
(eventId, occurredAt, muterId, mutedId; key = muterId, aggregateType `Mute`).

**Projections** (no cross-BC reads; one atomic upsert per event, newest `occurredAt` per pair wins, undo = tombstone
`active = false`: redelivered / late / out-of-order events never resurrect or drop a pair):

| BC | Table(s) | Writer | Used by |
|---|---|---|---|
| Feed | `feed_blocks`, `feed_mutes` (V26) | `BlockMuteProjection` via `SocialEventConsumer` (invalidates `BlockMuteLookup`: both users of a block, the muter of a mute) | list SQL, `PostVisibility`, `PostService.views`, SSE |
| Notification | `notification_blocks`, `notification_mutes` (V27) | `ActorRestrictionProjection` via `NotificationEventConsumer` | creation (`NotificationPolicy.withoutSuppressed`), read queries |
| UserManagement | `user_blocks` (V28) | `UserBlockProjection` via `BlockEventConsumer` (`user-blocks-group`) | people search |

**What is hidden where** (viewer V; "block" = V blocks X or X blocks V):

| View | Block (both directions) | Mute (V muted X) |
|---|---|---|
| `GET /feed` all + following, `GET /feed/stream` (SSE, both scopes) | X's posts and REPOSTs of X's posts | same |
| `GET /hashtags/{tag}/posts`, `GET /search/posts` | X's posts/quotes | same |
| `GET /trends/hashtags` | X's posts not counted (posts and authors) | same |
| `GET /social/suggestions` | X never suggested, either way (Neo4j, immediate) | X never suggested |
| `GET /notifications`, unread count, notification SSE | none created either way; existing ones hidden | none from X; existing hidden |
| `GET /search/users` | X not found, either way | not affected |
| `GET /bookmarks` | X's posts and REPOSTs of them skipped (rows kept) | not affected |
| `GET /posts/{id}/comments` | X's comments left out; on X's post -> 404 | not affected |
| `GET /posts/{id}` | 404 (also a REPOST item of X's post) | visible |
| embedded `original` of a QUOTE / quoted post of a REPOST-of-QUOTE | null, with `originalAuthorId` and the legacy snapshot fields ("unavailable", like a deleted original); SSE sends the same stripped variant | shown |
| like/dislike, comment, reply, comment like, repost, quote, bookmark | 404 (`Post/Comment/Parent comment not found`), indistinguishable from a deleted target; removing an own earlier reaction/bookmark/repost still works | allowed |
| `@mention` of X | the post is created and indexed; no MENTION notification | X's mentions of V do not notify V |

Lists filter in SQL BEFORE the LIMIT (`PostRepository.LISTED_FOR_VIEWER` / `NOT_BLOCKED_FOR_VIEWER` over the uncorrelated
`HIDDEN_FROM_VIEWER` / `BLOCKED_WITH_VIEWER` subqueries, evaluated once per query; notifications: `JdbcActorRestrictions.
NOT_SUPPRESSED`, three PK lookups per row), so keyset/offset pages stay exact. Point checks (`PostVisibility`) cost one query
(+ the original of a REPOST). `PostService.views` adds one query only when the page embeds originals (budget <= 11). The SSE
filter uses `BlockMuteLookup` (per-viewer cache, `app.feed.restrictions-cache-ttl` 5 s, invalidated on the consuming
instance), so a change applies to open streams without reconnecting. Not filtered on purpose: profiles (`GET /users/{id}`),
follower/following lists, counters (likes, comments, reposts), "mark all read". Retractions (unlike/unfollow) are applied even
during a block, so an undone like never reappears after an unblock.

**Privacy:** `GET /social/relationship/{userId}` returns only the caller's side (`blocked`/`muted` = BY the caller); there is no
"blocked by" flag. The blocked user only notices the unavoidable: follow 403 and the other side's content gone (404 / missing).

**Lag:** the graph (follows removed, follow refused, suggestions, `/social/blocks`, relationship) changes synchronously with
the REST call. The Feed / Notification / search projections follow via Neo4j outbox -> forwarder (AFTER_COMMIT wake-up) ->
Postgres outbox -> relay (wake-up) -> Kafka -> consumer: typically well under a second; longer only while Postgres/Kafka are
down (nothing is lost). Until then the old visibility applies (clients should hide the user optimistically after the 204);
interactions in that window are not undone later (their notifications are hidden by the read side).

**API** (all authenticated, acting user = JWT principal):
- `PUT /api/v1/social/block/{userId}` -> 204 (idempotent); self 400; unknown user 404; malformed id 400.
- `DELETE /api/v1/social/block/{userId}` -> 204 always. `GET /api/v1/social/blocks` -> `[{userId, username, avatarUrl}]` (by username).
- `PUT|DELETE /api/v1/social/mute/{userId}`, `GET /api/v1/social/mutes`: same contract.
- `GET /api/v1/social/relationship/{userId}` -> `{userId, following, followedBy, blocked, muted}`, always 200 (self/unknown: all false).
- `POST /api/v1/social/follow` across a block (either direction) -> 403 `{"error": "You cannot follow this user", ...}`.

Tests: `UserNodeTest`, `SocialGraphServiceTest`, `SocialOutboxForwarderTest`, `OutboxEventWriterTest`, `DomainEventWireFormatTest`,
`NotificationPolicyTest`, `NotificationEventConsumerTest`, `PostServiceTest`, `FeedSseServiceTest`, `BlockMuteLookupTest`,
`UserControllerSecurityTest` (web), `BlockMutePostgresTest` (Feed projection, every list, pages, bookmarks, comments, trends),
`NotificationPostgresTest` (5A section), `UserSearchPostgresTest`, `BlockMuteIntegrationTest` (full stack incl. the Cypher).
