# PROJECT_CONTEXT.md

## Project Summary

This repository contains the backend for an urgent job service.

The project is being designed as an MSA-based backend where each service owns its own database boundary and communicates with other services through API or event contracts.

The current repository is in an early backend setup stage. Implemented service directories are:

- `auth-service`
- `member-service`
- `job-service`
- `matching-service`

The broader domain and service boundaries are currently represented in:

- `docs/urgent_job_service_msa_erd_v1.drawio`

Architecture notes:

- `docs/architecture/service-package-structure.md`
- `docs/architecture/mvp-domain-flow.md`
- `docs/architecture/matching-application-design.md`

## Domain

The service domain is urgent job matching.

The system is expected to support users who need urgent work to be filled, workers who can accept jobs, and the operational flows around matching, work progress, payment, notifications, chat, and support.

Expected domain areas from the ERD:

- authentication and account access
- user profiles and user-owned information
- job posting and job search
- matching between jobs and workers
- work execution and work status tracking
- payment and settlement
- notifications
- chat
- customer support

## Current Stage

The project is currently in the initial backend scaffolding stage.

Current state:

- `auth-service` exists as a Spring Boot service.
- `auth-service` has implemented the initial LOCAL/OAuth authentication and signup flow.
- `member-service` exists as a Spring Boot service.
- `member-service` has implemented the initial member, owner, worker, and location profile flow.
- `job-service` has implemented the initial job posting domain.
- `matching-service` has service-owned MySQL and Flyway configuration, the application persistence model, and the worker-facing application create, read, list, and cancel APIs.
- The repository has a first ERD draft for the MSA design.
- The matching application domain has a focused ERD and implementation design that supersede the application and scoring tables in the first ERD draft.
- A repository-wide verification script exists at `docs/scripts/verify.sh`.
- A local Docker Compose file exists at `compose.local.yml` for auth/member/job/matching MySQL instances and auth/matching Redis instances.
- `.env.example` documents the local runtime environment variables; the real `.env` file is ignored by Git.
- `scripts/local-run.sh` loads `.env` and runs each service locally.
- The service package structure is defined in `docs/architecture/service-package-structure.md`.
- `auth-service` tests use Testcontainers with MySQL and Redis for integration-test dependencies.
- `member-service` tests use Testcontainers with MySQL for the test datasource.
- Agent work instructions exist in `AGENTS.md`.
- Agent failure memory, decision memory, and checklist memory exist under `docs/agent/`.
- Repository-specific CodeRabbit review settings exist in `.coderabbit.yaml`.

## Current Technical State

Current `auth-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- MySQL
- Redis
- Flyway
- Testcontainers MySQL for integration-test datasource
- Testcontainers Redis for integration-test Redis access
- Spring Boot Actuator
- Bean Validation
- Lombok
- Springdoc OpenAPI

Current `member-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- MySQL
- Flyway
- Testcontainers MySQL for integration-test datasource
- Spring Boot Actuator
- Bean Validation
- Lombok

Current `matching-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Data JPA
- MySQL
- Redis
- Flyway
- Testcontainers MySQL for integration-test datasource
- Testcontainers Redis for integration-test Redis access
- Spring Boot Actuator
- Bean Validation
- Lombok
- Spring Security
- Springdoc OpenAPI

Current matching application implementation:

- JWT-authenticated `WORKER` members can create, read, list, and cancel their own applications.
- Application creation validates an `ACTIVE` `WORKER` through the existing member-service internal API.
- The matching-service client for the job-service application-admission contract is implemented. The job-service endpoint still needs to be implemented by the job domain owner before end-to-end application creation can run.
- Application creation and cancellation persist status history and a `PENDING` Outbox event in the same local transaction.
- After the application transaction commits, the matching-service projects `APPLIED` application IDs into a job-specific Redis Set and synchronizes the current score ranking. Cancellation removes the ID and recalculates the remaining ranking.
- A scheduled recovery rebuilds each application Set from MySQL, which remains the source of truth. It restores the latest current-policy `READY` score batch or recalculates when the active applications and score snapshots differ. Score calculation commits before Redis projection, and Redis failures are logged without rolling back an application or completed score batch.
- The exact cancellation deadline and penalty policy remain undecided. The current API allows cancellation only while the application is `APPLIED`.
- An owner can select an `APPLIED` applicant through the manual matching endpoint. This creates one `MANUAL`, `PENDING` matching per application, records matching status history, and preserves the selected score batch and `READY` snapshot when available.
- Manual candidate selection does not reserve recruitment capacity or complete a match. A worker cancellation atomically cancels a related `PENDING` matching before canceling the application.
- A worker can list and read only their own matching proposals and can idempotently decline a `PENDING` proposal. Declining records a `DECLINED` matching history while the application remains `APPLIED`.
- A worker matching acceptance endpoint and a persisted confirmation Saga now coordinate recruitment-seat reservation, payment locking, scheduled-work creation, chat-room creation, and reverse-order compensation. Only a fully completed Saga changes the matching to `CONFIRMED` and the application to `SELECTED` and stores `ApplicationSelected` and `MatchConfirmed` Outbox events.
- Confirmation commands persist separate stable idempotency keys for forward and compensation steps and retain external resource IDs before advancing. A lease prevents concurrent coordinators. Unknown outcomes resume the same forward command without premature compensation, while definitively rejected commands compensate known resources and start a new attempt only after compensation succeeds.
- The current repository does not yet contain payment, work, or chat services, and the job-service seat-reservation contract is not implemented. Matching acceptance therefore fails closed until those service owners implement the documented internal contracts; no temporary successful confirmation is fabricated.
- A shared-secret internal recruitment-completion endpoint rejects remaining `APPLIED` applications and cancels their `PENDING` matching proposals in one transaction. It stores the completed job version as a durable barrier against late applications, rebuilds the Redis queues once per job, persists status histories and `ApplicationRejected` Outbox events, and refuses completion while a confirmation Saga has an unknown outcome or unfinished compensation. A higher job version permits applications after reopening. The job-service producer remains owned by the job domain.
- A database-leased Outbox relay publishes domain-event envelopes to the `matching:domain-events` Redis Stream. It preserves aggregate revision order, retries failures with capped exponential backoff, recovers expired leases, and provides at-least-once delivery with stable `eventId` values for consumer deduplication. Local Redis uses synchronous AOF persistence; production event Redis must provide equivalent durability and a no-eviction policy.
- Versioned score batch and application score snapshot persistence is implemented with `CALCULATING`/`READY`/`FAILED` lifecycle states. Missing external inputs remain nullable and are distinguished through `missing_inputs` instead of fabricated zero scores.
- The initial `application-time-v1` policy calculates a relative score from deterministic application order and creates a new immutable batch on recalculation. Ranked MySQL reads use total score, application time, and application ID order.
- The latest current-policy `READY` score batch is projected into a batch-specific Redis Sorted Set with `scoreBatchId` and `policyVersion` metadata. Replacing a ranking atomically removes the previous batch key, and a per-job fencing token rejects stale replacements after a distributed lock lease expires.
- JWT-authenticated `OWNER` members can query their own job's active applicants through list and detail APIs. The list pins an immutable `READY` score batch across pages, returns scored applicants first, and retains failed or unscored applicants with nullable score fields.
- Application admissions now preserve the job owner member ID as an external snapshot for applicant-query isolation. The database column stays nullable only for pre-migration rows; those rows remain hidden from owner queries until a job-service ownership contract supports backfill.
- `member-service` provides a batch internal contract that returns only active worker IDs and names for applicant display; missing summaries remain nullable without exposing email, phone, or location.
- Multi-factor scoring and its input adapters remain later implementation units. Missing rating, experience, no-show, online-status, and ETA inputs must be connected when their owning service contracts become available.

The current scaffold matches the decided technology baseline in `docs/agent/decisions.md`.

## Service Map

Implemented:

- `auth-service`: authentication service
- `member-service`: member profile service
- `job-service`: job posting service
- `matching-service`: application and matching service

Planned or represented in the ERD:

- `work-service`
- `payment-service`
- `notification-service`
- `chat-service`
- `support-service`

This service map is provisional. Use `docs/agent/decisions.md` for confirmed decisions that override this document.

## Current ERD Notes

The ERD describes service ownership and relationship types using:

- `PK`: primary key
- `FK`: foreign key inside the same service database
- `EXT`: external service ID reference, not a physical database foreign key
- `UQ`: unique constraint

For the matching application domain, use `docs/architecture/matching-application-erd.drawio` and `docs/architecture/matching-application-design.md`. The application and scoring tables in the first repository-wide ERD are retained as an early draft and are not the implementation contract.

## Auth Service Context

`auth-service` is currently the authentication service.

Expected responsibilities:

- account authentication
- login/logout flow
- JWT access token issuance and validation
- refresh token renewal flow
- security configuration
- password hashing
- refresh token storage, rotation, expiration, and revocation

Current implementation state:

- `auth-service` owns authentication state, LOCAL credentials, OAuth connections, verification flows, JWT issuance, refresh token rotation, and logout.
- Initial auth account, credential, OAuth connection, refresh token entities, and related enums have been added.
- Initial auth account, credential, OAuth connection, and refresh token repositories have been added.
- Initial auth request/response DTOs and member-service client DTOs have been added.
- Redis-backed verification code service has been added.
- Initial JWT access token issuance/validation, refresh token rotation, and refresh token hashing support have been added.
- Initial member-service REST client support has been added.
- Initial member-service REST client calls include a shared internal secret header for service-to-service APIs.
- LOCAL signup/login, token reissue, and logout service layer support has been added.
- LOCAL signup requires email verification, SMS verification, password input, and role-specific additional information before final account creation.
- Signup calls member-service first and compensates by deleting the created member if auth-service persistence fails afterward.
- Signup service-to-service calls run outside the auth database transaction; auth account, credential or OAuth connection, and refresh token persistence use a separate short transaction.
- Initial auth controllers, Swagger/OpenAPI documentation, and basic stateless security configuration have been added.
- Auth API responses and controller-level errors use the common `ApiResponse` envelope.
- Auth security 401/403 responses are written as the common `ApiResponse` envelope.
- Core auth logic tests cover refresh token rotation, LOCAL login, signup verification checks, token hashing, and JWT validation.
- Refresh token reissue checks account status and locks the refresh token row during rotation.
- Verification code sending has a Redis-backed short rate limit, and verification attempts are limited before the code is invalidated.
- Initial KAKAO/NAVER OAuth2 login support has been added.
- OAuth2 login connects to an existing OAuth connection, links same-email accounts when no connection exists, or issues a Redis-backed signup ticket for new users.
- OAuth2 signup ticket completion supports OWNER/WORKER signup without creating `LocalCredential`.
- OAuth2 signup tickets retain their original 30-minute expiration when restored after a retryable owner-signup rejection.
- OAuth2 provider access currently uses provider authorization code exchange through backend API calls rather than Spring Security's redirect-based OAuth2 login flow.
- Initial Flyway schema migration has been added.

Auth-related decisions are recorded in `docs/agent/decisions.md`.

## Member Service Context

`member-service` stores member profile data owned by the member domain.

Expected responsibilities:

- member basic information storage
- owner profile storage
- worker profile storage
- location storage
- member information lookup

Current implementation state:

- `member-service` owns member basic information, role-specific profile data, worker preferences, worker available times, and location data.
- Initial member, owner, worker, and location entities have been added.
- Initial repository, service, and controller layers have been added.
- Basic member-service security configuration permits Swagger and internal member APIs.
- `member-service` verifies auth-service JWT access tokens directly for the current `/api/members/me` flow.
- Internal member APIs under `/api/members/internal/**` require the shared `X-Internal-Secret` header.
- An internal signup compensation endpoint can delete a member created before auth-service persistence fails.
- Signup compensation deletion is covered by an integration test that verifies member, profile, location, and worker child records are deleted.
- Member API responses, controller-level errors, internal API secret failures, and security 401/403 responses use the common `ApiResponse` envelope.
- Location data stores both address and latitude/longitude so address can be used for display and coordinates can support future radius-based search.
- OWNER signup stores a user-entered store name and accepts only business numbers that the National Tax Service status API reports as operating.
- Business status lookup does not verify representative identity or business ownership.
- Initial Flyway schema migration has been added.

Member signup design notes are recorded in `docs/architecture/auth-member-signup-design.md`.

## Current Persistence Dependencies

Current persistence-related dependencies:

- MySQL for relational data
- Redis for cache/session/token-related use cases where appropriate
- Flyway for database migrations

## Near-Term Priorities

Likely next steps:

- Replace direct member-service JWT validation with API Gateway verified identity propagation when the gateway is introduced.
- Keep project and agent documents aligned as decisions are made.

## Open Questions

These questions are not yet settled in code:

- What deployment target and environment strategy will be used?
