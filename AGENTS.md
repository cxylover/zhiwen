# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

ZhiGuang (知光) — a knowledge-sharing community platform backend API. Java 21, Spring Boot 3.2.4, Maven build.

## Build & Run

```bash
mvn clean package          # build
mvn spring-boot:run        # run dev server
java -jar target/zhiguang-1.0-SNAPSHOT.jar   # run packaged jar
mvn test                   # run tests (only 2 test classes exist)
```

Runtime requires: MySQL 8.0 (3306), Redis (6379), Kafka (9092), Elasticsearch (9201), Canal (11111), Alibaba Cloud OSS.

## Architecture

Base package: `com.tongji`. Organized by domain, not by technical layer:

```
auth/       — JWT RS256 auth (dual token: 15min access + 7d refresh), verification codes
user/       — User domain model and persistence
knowpost/   — Knowledge post CRUD + feed (core content domain)
relation/   — Follow/unfollow via Outbox + Canal + Kafka
counter/    — Like/favorite counters (Kafka aggregation → Redis SDS binary)
cache/      — 3-level feed cache: Caffeine → Redis page → Redis fragment, hotkey detection
search/     — Elasticsearch keyword search + autocomplete
llm/        — RAG Q&A (DashScope embeddings + DeepSeek chat, SSE streaming)
profile/    — User profile management
storage/    — Alibaba Cloud OSS presigned URL generation
common/     — Global exception handler, error codes
config/     — Infrastructure configs (ES, Redisson, thread pools)
```

Each domain typically has: `api/` (controllers + DTOs), `service/` + `service/impl/`, `mapper/` (MyBatis), `model/` (domain), `config/`.

## Database

ORM: MyBatis with XML mappers at `src/main/resources/mapper/*.xml`. Config: `map-underscore-to-camel-case: true`. Schema DDL: `db/schema.sql`. Snowflake IDs for know_posts (not DB auto-increment).

## API Conventions

All endpoints under `/api/v1/`. Controllers extract user via `@AuthenticationPrincipal Jwt jwt` + `JwtService.extractUserId(jwt)`. DTOs use Java records. Validation via Jakarta Bean Validation annotations. Global error handling via `@RestControllerAdvice` with `BusinessException` + `ErrorCode` enum.

## Auth / Security

Stateless JWT (RS256). RSA keys at `src/main/resources/keys/`. Public endpoints defined in `SecurityConfig.java` — everything else requires Bearer token. CSRF disabled, CORS open, stateless sessions.

## Key Patterns

- **Outbox + Canal + Kafka**: Relation events write to `outbox` table in same txn; Canal tails binlog → Kafka → consumer updates denormalized tables.
- **Kafka write aggregation**: Like/fav ops → Kafka topic → consumer batches → Redis + bitmap dedup.
- **3-level feed cache**: Caffeine L1 → Redis page L2 → Redis fragment L3. `HotKeyDetector` extends TTL for hot keys. Single-flight lock prevents stampede.
- **SDS binary counters**: 5 user-level counters stored as 20-byte binary string in Redis (big-endian).
- **RAG**: Post content chunked → DashScope embedding → ES vector index. Query: semantic search → DeepSeek prompt → SSE stream.
- **Presigned upload**: Backend generates OSS presigned PUT URL; frontend uploads directly.
- **Conditional features**: RAG gated by `feature.rag.enabled=true` via `@ConditionalOnProperty`.

## Code Style

Lombok used extensively (`@RequiredArgsConstructor`, `@Getter`, `@Slf4j`). No Lombok in DTOs — use Java records instead. Feature modules are self-contained; cross-module communication via Kafka topics or direct service calls within the same JVM.

## Configuration

All runtime config in `src/main/resources/application.yml` (datasource, Redis, Kafka, ES, AI model, Canal, JWT, OSS, caching). Secrets (AI API keys, OSS credentials) are also in this file.
