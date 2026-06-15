# PROJECT_CONTEXT.md

## Project Summary

This repository contains the backend for an urgent job service.

The project is being designed as an MSA-based backend where each service owns its own database boundary and communicates with other services through API or event contracts.

The current repository is in an early backend setup stage. The only implemented service directory is:

- `auth-service`

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
- The repository has a first ERD draft for the MSA design.
- A repository-wide verification script exists at `docs/scripts/verify.sh`.
- The service package structure is defined in `docs/architecture/service-package-structure.md`.
- `auth-service` tests use Testcontainers with MySQL for the test datasource.
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
- Spring Boot Actuator
- Bean Validation
- Lombok

The current scaffold matches the decided technology baseline in `docs/agent/decisions.md`.

## Service Map

Implemented:

- `auth-service`: authentication service

Planned or represented in the ERD:

- `user-service`
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

- The service currently contains only generated Spring Boot skeleton code.
- Security, domain model, API contracts, persistence model, and token strategy are not yet implemented in code.

Auth-related decisions are recorded in `docs/agent/decisions.md`.

## Current Persistence Dependencies

Current persistence-related dependencies:

- MySQL for relational data
- Redis for cache/session/token-related use cases where appropriate
- Flyway for database migrations

## Near-Term Priorities

Likely next steps:

- Apply the decided service package structure to `auth-service`.
- Define access token and refresh token handling details.
- Define common API response and error response shape.
- Add first Flyway migration when the initial auth schema is confirmed.
- Add Redis Testcontainers support when tests start depending on Redis behavior.
- Keep project and agent documents aligned as decisions are made.

## Open Questions

These questions are not yet settled in code:

- Where will refresh tokens be stored, if used?
- What is the common API response format?
- What is the common error format?
- Will service-to-service communication initially use synchronous HTTP, events, or both?
- What deployment target and environment strategy will be used?
