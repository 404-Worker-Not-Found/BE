# PROJECT_CONTEXT.md

## Project Summary

This repository contains the backend for an urgent job service.

The project is being designed as an MSA-based backend where each service owns its own database boundary and communicates with other services through API or event contracts.

The current repository is in an early backend setup stage. Implemented service directories are:

- `auth-service`
- `member-service`

The broader domain and service boundaries are currently represented in:

- `docs/urgent_job_service_msa_erd_v1.drawio`

Architecture notes:

- `docs/architecture/service-package-structure.md`

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
- `auth-service` has generated application and test skeletons.
- `member-service` exists as a Spring Boot service.
- `member-service` has initial member, owner, worker, and location entities.
- The repository has a first ERD draft for the MSA design.
- A repository-wide verification script exists at `docs/scripts/verify.sh`.
- A local Docker Compose file exists at `compose.local.yml` for auth/member MySQL instances and auth Redis.
- `.env.example` documents the local runtime environment variables; the real `.env` file is ignored by Git.
- The service package structure is defined in `docs/architecture/service-package-structure.md`.
- `auth-service` tests use Testcontainers with MySQL and Redis for integration-test dependencies.
- `member-service` tests use Testcontainers with MySQL for the test datasource.
- Agent work instructions exist in `AGENTS.md`.
- Agent failure memory, decision memory, and checklist memory exist under `docs/agent/`.

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

The current scaffold matches the decided technology baseline in `docs/agent/decisions.md`.

## Service Map

Implemented:

- `auth-service`: authentication service
- `member-service`: member profile service

Planned or represented in the ERD:

- `job-service`
- `matching-service`
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

## Auth Service Context

`auth-service` is currently the first implemented service.

Expected responsibilities:

- account authentication
- login/logout flow
- JWT access token issuance and validation
- refresh token renewal flow
- security configuration
- password hashing
- refresh token storage, rotation, expiration, and revocation

Current implementation state:

- The service currently contains generated Spring Boot application and test skeletons.
- The agreed package structure has been scaffolded in `auth-service` with `package-info.java` files so package directories are tracked.
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
- Initial auth controllers, Swagger/OpenAPI documentation, and basic stateless security configuration have been added.
- Auth API responses and controller-level errors use the common `ApiResponse` envelope.
- Auth security 401/403 responses are written as the common `ApiResponse` envelope.
- Core auth logic tests cover refresh token rotation, LOCAL login, signup verification checks, token hashing, and JWT validation.
- Refresh token reissue checks account status and locks the refresh token row during rotation.
- Verification code sending has a Redis-backed short rate limit, and verification attempts are limited before the code is invalidated.
- Initial KAKAO/NAVER OAuth2 login support has been added.
- OAuth2 login connects to an existing OAuth connection, links same-email accounts when no connection exists, or issues a Redis-backed signup ticket for new users.
- OAuth2 signup ticket completion supports OWNER/WORKER signup without creating `LocalCredential`.
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

- The service currently contains generated Spring Boot application and test skeletons.
- `member-service` owns member basic information, role-specific profile data, worker preferences, worker available times, and location data.
- Initial member, owner, worker, and location entities have been added.
- Initial repository, service, and controller layers have been added.
- Basic member-service security configuration permits Swagger and internal member APIs.
- `member-service` verifies auth-service JWT access tokens directly for the current `/api/members/me` flow.
- Internal member APIs under `/api/members/internal/**` require the shared `X-Internal-Secret` header.
- An internal signup compensation endpoint can delete a member created before auth-service persistence fails.
- Member API responses, controller-level errors, internal API secret failures, and security 401/403 responses use the common `ApiResponse` envelope.
- Location data stores both address and latitude/longitude so address can be used for display and coordinates can support future radius-based search.
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
