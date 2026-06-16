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
- Initial auth account, credential, OAuth connection, refresh token entities, and related enums have been added.
- Initial auth account, credential, OAuth connection, and refresh token repositories have been added.
- Initial auth request/response DTOs and member-service client DTOs have been added.
- Redis-backed verification code service has been added.
- Initial JWT access token issuance/validation and refresh token hashing support have been added.
- Security, controller, Flyway schema migrations, signup, and login services are not yet implemented.

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
- Initial member, owner, worker, and location entities have been added.
- Initial repository, service, and controller layers have been added.
- API contracts and Flyway schema migrations are not yet implemented.

Member signup design notes are recorded in `docs/architecture/auth-member-signup-design.md`.

## Current Persistence Dependencies

Current persistence-related dependencies:

- MySQL for relational data
- Redis for cache/session/token-related use cases where appropriate
- Flyway for database migrations

## Near-Term Priorities

Likely next steps:

- Define common API response and error response shape.
- Add first Flyway migration when the initial auth schema is confirmed.
- Keep project and agent documents aligned as decisions are made.

## Open Questions

These questions are not yet settled in code:

- Where will refresh tokens be stored, if used?
- What is the common API response format?
- What is the common error format?
- Will service-to-service communication initially use synchronous HTTP, events, or both?
- What deployment target and environment strategy will be used?
