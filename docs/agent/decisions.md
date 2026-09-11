# Decision Memory

This file records decisions the project has already made.

Decisions may be product, architecture, technology, repository, verification, or agent-operation decisions. Do not use this file for current-state summaries, repeated mistake reports, or task checklists.

Use this format:

```md
## YYYY-MM-DD - Short Title

Decision:
- What was decided.

Reason:
- Why this decision was made.

Implication for agents:
- What future agents should do or avoid because of this decision.

Related files:
- Optional file paths.
```

## 2026-06-15 - Backend Repository Structure

Decision:
- Use a monorepo for backend services.

Reason:
- Only `auth-service` exists currently.
- Shared development and verification are simpler at this stage.

Implication for agents:
- Do not propose separate repositories unless there is a strong operational reason.
- Add new backend services under this repository unless a later decision changes the repository strategy.

Related files:
- `auth-service`
- `docs/scripts/verify.sh`

## 2026-06-15 - Service Boundaries Are Not Final

Decision:
- Planned service boundaries are provisional.
- Only `auth-service` currently exists in code.

Reason:
- Domain boundaries still need validation through implementation.
- The ERD is a first design draft, not a final service contract.

Implication for agents:
- Do not assume all planned services already exist.
- Explain data ownership and transaction boundaries when proposing service separation.
- Treat service names from the ERD as planned candidates until implemented or confirmed.

Related files:
- `docs/PROJECT_CONTEXT.md`
- `docs/urgent_job_service_msa_erd_v1.drawio`
- `auth-service`

## 2026-06-15 - Service Data Ownership

Decision:
- Each service should own its data model and persistence boundary.
- Physical database foreign keys should stay inside a single service database.
- Cross-service references should be stored as external IDs, not database-level foreign keys.

Reason:
- Service autonomy and independent persistence boundaries are core to the planned MSA direction.

Implication for agents:
- Do not add physical foreign keys across service boundaries.
- When proposing cross-service workflows, explain API/event contracts and consistency boundaries.
- Be explicit about which service owns each piece of data.

Related files:
- `docs/urgent_job_service_msa_erd_v1.drawio`
- `docs/PROJECT_CONTEXT.md`

## 2026-06-15 - JWT Authentication Strategy

Decision:
- Use Access Token + Refresh Token authentication.

Reason:
- Supports stateless API authentication while allowing token renewal.

Implication for agents:
- Authentication proposals should assume JWT-based authentication.
- Avoid session-based authentication designs unless explicitly requested.
- When proposing refresh token behavior, explain storage, rotation, expiration, and revocation.

Related files:
- `auth-service`

## 2026-06-15 - Technology Baseline

Decision:
- Use Java 17.
- Use Spring Boot 4.1.0.
- Use MySQL.
- Use Redis.

Reason:
- This matches the current `auth-service` scaffold and build configuration.

Implication for agents:
- Do not suggest incompatible stack changes without justification.
- If proposing a Java or Spring Boot version change, explain the migration reason and expected impact.
- Prefer libraries and patterns compatible with Java 17 and Spring Boot 4.1.0 unless a later decision changes the baseline.

Related files:
- `auth-service/build.gradle`
- `docs/PROJECT_CONTEXT.md`

## 2026-06-15 - Backend Development Conventions

Decision:
- Use constructor injection.
- Keep controller, service, repository, entity, and DTO responsibilities separate.
- Put business logic in services, not controllers.
- Validate request DTOs at the boundary.
- Avoid exposing JPA entities directly through API responses.
- Use Flyway for schema changes.
- Do not modify existing shared Flyway migrations unless explicitly approved.

Reason:
- These conventions reduce coupling, keep API boundaries explicit, and make backend changes easier to review.

Implication for agents:
- Follow these conventions for new backend code.
- Explain any exception before applying it.

Related files:
- `auth-service`

## 2026-06-15 - Security Conventions

Decision:
- Treat authentication and authorization changes as high-risk.
- Do not disable security to make tests pass.
- Do not log passwords, tokens, refresh tokens, authorization headers, or secrets.
- Use environment variables or profile-specific config for sensitive values.

Reason:
- Authentication code and secret handling create high-impact failure modes.

Implication for agents:
- Keep production security behavior intact unless explicitly changed by a confirmed requirement.
- Prefer test-specific security setup over weakening production security config.

Related files:
- `auth-service`

## 2026-06-15 - Agent Document Responsibilities

Decision:
- `AGENTS.md` defines AI behavior rules.
- `docs/PROJECT_CONTEXT.md` describes the current project state.
- `docs/agent/decisions.md` records decisions.
- `docs/agent/failure-memory.md` records repeated mistakes identified by the user.
- `docs/agent/checklists.md` records verification checklists.
- `docs/agent/coding-rules.md` records AI-facing backend coding rules.

Reason:
- Each document has a different purpose and update cadence.

Implication for agents:
- Put new information in the document matching its purpose.
- Do not duplicate the same rule across multiple documents unless a short reference is needed.

Related files:
- `AGENTS.md`
- `docs/PROJECT_CONTEXT.md`
- `docs/agent/decisions.md`
- `docs/agent/failure-memory.md`
- `docs/agent/checklists.md`
- `docs/agent/coding-rules.md`

## 2026-06-15 - Decision Priority

Decision:
- When `docs/PROJECT_CONTEXT.md` and `docs/agent/decisions.md` conflict, follow `docs/agent/decisions.md` and report the conflict.

Reason:
- `docs/PROJECT_CONTEXT.md` describes current state and may become stale. `docs/agent/decisions.md` records what has been decided.

Implication for agents:
- Treat `docs/agent/decisions.md` as the higher-priority source for decisions.
- Do not silently resolve conflicts.

Related files:
- `docs/PROJECT_CONTEXT.md`
- `docs/agent/decisions.md`

## 2026-06-15 - Repository Verification Entry Point

Decision:
- Use `./docs/scripts/verify.sh` as the repository-wide verification command.

Reason:
- A single verification entry point lets humans, agents, and future CI run the same check.

Implication for agents:
- Run `./docs/scripts/verify.sh` before completing non-documentation tasks unless the user explicitly asks not to.
- Add new service checks or smoke tests to `docs/scripts/verify.sh` as the project grows.
- If verification fails, report the failing step and whether it appears related to the current task.

Related files:
- `docs/scripts/verify.sh`
- `docs/agent/checklists.md`
- `AGENTS.md`

## 2026-06-15 - Test Datasource Strategy

Decision:
- Use Testcontainers with MySQL for auth-service and member-service integration-test datasource setup.
- Use Testcontainers with Redis for auth-service tests that depend on Redis behavior.

Reason:
- The services use MySQL, JPA, and Flyway, so tests should run against a real MySQL-compatible database instead of an H2 approximation or a developer-managed local database.
- Auth verification, OAuth signup ticket, and other Redis-backed auth flows need reproducible Redis behavior in tests.
- Testcontainers keeps local and future CI verification reproducible.

Implication for agents:
- Do not replace the test datasource with H2 or a manually managed local MySQL database unless a later decision changes the strategy.
- Put shared Spring Boot integration-test container setup in test support code.
- Use Redis Testcontainers support for Redis-dependent auth-service integration tests.

Related files:
- `auth-service/build.gradle`
- `auth-service/src/test/java/com/workernotfound/auth/support/IntegrationTestSupport.java`
- `member-service/build.gradle`
- `member-service/src/test/java/com/workernotfound/member/support/IntegrationTestSupport.java`
- `docs/scripts/verify.sh`

## 2026-06-15 - Failure Memory Requires User Identification

Decision:
- Do not let agents independently decide that a mistake should be recorded in `docs/agent/failure-memory.md`.
- When the user identifies a repeated mistake, propose an entry and record it only after user approval.

Reason:
- Agents often cannot reliably judge whether their own behavior is a recurring mistake. User confirmation prevents incorrect or noisy rules from accumulating.

Implication for agents:
- Propose failure-memory entries only when the user identifies a repeated mistake.
- Do not update `docs/agent/failure-memory.md` without explicit user request or clear approval.

Related files:
- `docs/agent/failure-memory.md`
- `AGENTS.md`

## 2026-06-15 - Branch Strategy

Decision:
- Use Git Flow for collaboration.
- Use `develop` as the integration branch for development work.
- Create work branches from `develop`.
- Match the branch type to the work type.
- Include the issue number in the branch name using `{type}/#{issue-number}-{short-description}`, such as `chore/#2-auth-service-package-structure`.

Reason:
- A consistent branch flow keeps work isolated and makes integration testing through `develop` predictable.

Implication for agents:
- Do not work directly on `develop` unless explicitly requested.
- For a new unit of work, follow this flow: create issue, create issue-number branch from `develop`, work locally, commit and push, then open a PR to `develop`.
- Use branch types such as `feature/*`, `fix/*`, `docs/*`, `refactor/*`, `test/*`, `build/*`, `ci/*`, `chore/*`, or `environment/*` according to the work type.
- For chore tasks, use `chore/*` branches unless the user explicitly says otherwise.

Related files:
- `docs/agent/checklists.md`

## 2026-06-15 - Commit Convention

Decision:
- Use `type: 작업 내용` commit messages.
- Common types are `feat`, `fix`, `docs`, and `refactor`.
- Additional allowed types are `test`, `build`, `ci`, `chore`, and `environment`.

Reason:
- A consistent commit convention keeps history readable and makes review easier.

Implication for agents:
- Use commit messages like `feat: 노트 CRUD`.
- Use `docs` for documentation-only changes.
- Pick the most specific type for the change.

Related files:
- `docs/agent/checklists.md`

## 2026-06-15 - Issue Convention

Decision:
- Use issue titles in the format `[type] title`, such as `[chore] 스타일 시스템 추가`.
- Use the repository issue template for issue content.
- Write issue titles and bodies in Korean.

Reason:
- Consistent issue titles and content make planned work easier to scan and track.

Implication for agents:
- Match the issue type to the work type, such as `[feature]`, `[fix]`, `[docs]`, `[refactor]`, `[test]`, `[build]`, `[ci]`, `[chore]`, or `[environment]`.
- When creating or proposing issues, include issue type, feature description, task checklist, and reference links when available.
- Follow the issue template instead of inventing a new format.
- Use Korean for issue titles and body content unless the user explicitly requests another language.

Related files:
- `.github/ISSUE_TEMPLATE/task.md`
- `docs/agent/checklists.md`

## 2026-06-15 - Pull Request Convention

Decision:
- Use PR titles in the format `[type] PR 제목`, such as `[feat] 노트 CRUD`.
- Target `develop` for development PRs.
- Assign the PR author as the assignee in PR metadata.
- Use the repository PR template for PR content.
- Write PR titles and bodies in Korean.
- In the related issue section, reference issue numbers without auto-closing keywords such as `Close`, `Fixes`, or `Resolves`.
- Do not put command-specific AI verification notes, such as `./gradlew test passed`, in issue or PR bodies.
- Generic checklist text such as `테스트를 통과했나요?` is allowed in PR templates.

Reason:
- Consistent PR metadata makes review ownership and change intent clear.

Implication for agents:
- When creating or proposing PRs, use the `[type] title` format.
- Set the PR assignee to the PR author through PR metadata when tool access allows it.
- Include related issue, work purpose, work contents, optional screenshots, and checklist.
- Use plain issue references such as `- #2` instead of `Close #2` in PR bodies unless the user explicitly asks to auto-close the issue.
- Report command-specific verification results in the agent's final response, not as AI-flavored prose in issue or PR templates.
- Use Korean for PR titles and body content unless the user explicitly requests another language.

Related files:
- `.github/pull_request_template.md`
- `docs/agent/checklists.md`

## 2026-06-15 - Review and Merge Strategy

Decision:
- One teammate reviews each PR.
- After approval or after requested changes are addressed, the PR author merges the PR.
- Use squash-and-merge.
- Delete the branch after the PR is merged.
- For a new unit of work, recreate a fresh branch from the appropriate base branch.

Reason:
- This keeps review responsibility clear and keeps branch history tidy.

Implication for agents:
- Do not merge without review approval unless explicitly instructed.
- Prefer squash-and-merge when completing PRs.
- After merge, expect the work branch to be deleted before starting new work.

Related files:
- `docs/agent/checklists.md`

## 2026-06-16 - Service Package Structure

Decision:
- Use the service package structure defined in `docs/architecture/service-package-structure.md`.
- Organize service code by domain under `domain/{domain}` with controller, service, repository, entity, and dto packages.
- Use `global` for service-wide configuration, exception handling, response shape, and security.
- Use `external` for other service clients, events, Redis, and other external integrations.
- Follow the layer direction `Controller -> Service -> Repository -> Entity`.
- Do not reference upper layers from lower layers, and avoid skipping layers.
- Use Service CQRS naming with `ApplicationService`, `CommandService`, and `FindService` when the domain responsibility justifies separation.
- Split DTO packages into request and response.
- Use Java `record` as the default DTO form.
- Use DTO static factory methods for simple response conversion, and use converter classes only when conversion is complex or reused.
- Keep DTO builder or response assembly details out of Service use-case flow.

Reason:
- A shared internal structure makes services easier for the team to navigate.
- Domain-first packaging keeps related code close together while preserving layer responsibilities.
- CQRS-style service separation keeps command and query responsibilities clear as domains grow.
- Record DTOs and centralized conversion reduce boilerplate and prevent JPA entities from leaking through API responses.

Implication for agents:
- Read `docs/architecture/service-package-structure.md` before creating or changing service package structure.
- Apply the documented structure to new service code unless the user explicitly approves a different structure.
- If a different structure is needed, explain the reason and record the decision.
- Do not create empty packages or classes before they are needed.

Related files:
- `docs/architecture/service-package-structure.md`
- `AGENTS.md`
- `docs/PROJECT_CONTEXT.md`

## 2026-06-16 - AI-Facing Backend Coding Rules

Decision:
- Keep AI-facing backend coding rules in `docs/agent/coding-rules.md`.
- Function, naming, and error handling rules belong in `docs/agent/coding-rules.md`, not in the architecture package-structure document.

Reason:
- Function length, naming, and error handling guidance are implementation behavior rules for agents rather than service architecture structure.
- Keeping them in `docs/agent` prevents the architecture document from mixing structural decisions with code-style guidance.

Implication for agents:
- Apply the function, naming, and error handling rules during backend implementation work.
- Consult `docs/agent/coding-rules.md` when the task involves function structure, naming, or error handling rules, or when the rule is not already clear from the current context.
- Keep architecture documents focused on structure, ownership, boundaries, and package layout.

Related files:
- `docs/agent/coding-rules.md`
- `AGENTS.md`
- `docs/architecture/service-package-structure.md`

## 2026-06-16 - Initial Auth and Member Service Boundary

Decision:
- `auth-service` owns authentication accounts, LOCAL credentials, OAuth connections, verification flows, JWT issuance, refresh token rotation, and logout.
- `member-service` owns member basic information, role-specific profile data, worker preferences, worker available times, and location data.
- Cross-service references use external IDs such as `memberId`, not physical database foreign keys.

Reason:
- Authentication state and member profile data have different ownership, lifecycle, and persistence boundaries.
- Keeping physical foreign keys inside a service preserves service autonomy.

Implication for agents:
- Do not add member profile fields to auth-service entities unless they are required for authentication.
- Do not create physical database foreign keys between auth-service and member-service.
- When auth-service needs member data creation, call member-service through the agreed internal API contract.

Related files:
- `auth-service`
- `member-service`

## 2026-06-16 - Initial Service-to-Service Communication

Decision:
- Use synchronous REST for initial communication between `auth-service` and `member-service`.
- Put internal service-to-service APIs under `/api/{domain}/internal/**`.
- Protect internal service-to-service APIs with the `X-Internal-Secret` shared secret header at this stage.

Reason:
- REST keeps the first cross-service signup flow simple and explicit while the project is still in an early implementation phase.
- A shared secret header provides a minimal guard against direct external calls before API Gateway, service mesh, or mTLS is introduced.

Implication for agents:
- Do not expose internal APIs as unauthenticated public endpoints.
- Do not hardcode the internal secret; inject it from configuration or environment variables.
- Keep TODOs or extension points that allow this mechanism to be replaced by API Gateway, mTLS, or another service-to-service authentication mechanism later.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/external/client/member`
- `member-service/src/main/java/com/workernotfound/member/global/security`

## 2026-06-16 - Signup and OAuth Account Linking Policy

Decision:
- LOCAL signup is completed only after required email verification, SMS verification, password input, and role-specific additional information are provided.
- OAuth2 supports only KAKAO and NAVER.
- If an OAuth provider account is already connected, OAuth login signs in through that connection.
- If no OAuth connection exists but the provider email matches an existing account, connect the OAuth account to the existing auth account.
- If neither connection nor matching email exists, issue a Redis-backed OAuth signup ticket and complete signup after OWNER or WORKER additional information is provided.
- OAuth signup does not create `LocalCredential`.

Reason:
- The policy avoids pending onboarding accounts and keeps final signup atomic from the user's perspective.
- Same-email OAuth linking prevents duplicate auth accounts for the same user.
- OAuth users do not need LOCAL password credentials.

Implication for agents:
- Do not introduce `PENDING` member status for signup.
- Do not create `LocalCredential` during OAuth signup.
- Preserve the same-email OAuth linking behavior unless the product policy changes.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service`
- `auth-service/src/main/java/com/workernotfound/auth/domain/account/entity`

## 2026-06-16 - Local Development Infrastructure

Decision:
- Use a repository-root local Docker Compose file for shared local infrastructure.
- Keep auth-service and member-service databases separate, even in local development.
- Reserve port `8080` for a future API Gateway.
- Use `8081` for auth-service and `8082` for member-service in local development.
- Commit `.env.example` as the local environment variable template, but keep the real `.env` ignored.

Reason:
- A root compose file lets developers start and stop the cross-service local infrastructure together.
- Separate databases keep local development aligned with the MSA persistence boundary.
- Reserving `8080` avoids later port churn when an API Gateway is introduced.

Implication for agents:
- Add shared local infrastructure to the root compose file unless there is a clear service-specific reason not to.
- Do not commit real `.env` files or local secrets.
- Keep service ports aligned with the local convention unless the user explicitly changes it.

Related files:
- `compose.local.yml`
- `.env.example`

## 2026-06-16 - Common API Response Format

Decision:
- Use a common `ApiResponse<T>` envelope for auth-service and member-service APIs.
- Successful responses contain `success`, `status`, `code`, `message`, `data`, `path`, `timestamp`, and `reasons`.
- Successful responses use `success=true`, `code=SUCCESS`, and `message=요청이 성공적으로 처리되었습니다.`
- Error responses use `success=false`, an error code, an error message, request path, timestamp, and optional reasons.
- Security 401/403 responses should also use the common envelope instead of servlet default error bodies.

Reason:
- A consistent response contract makes Swagger testing, frontend integration, and service debugging easier.
- Security and validation failures should not return a different shape from controller responses.

Implication for agents:
- Wrap controller responses in the service-local `global.response.ApiResponse`.
- Add or update `global.exception` handling when introducing new business exceptions.
- Do not return JPA entities or raw DTOs directly from controllers.
- If an internal service client consumes a wrapped response, unwrap and validate the `data` field at the client boundary.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/global/response/ApiResponse.java`
- `auth-service/src/main/java/com/workernotfound/auth/global/exception`
- `member-service/src/main/java/com/workernotfound/member/global/response/ApiResponse.java`
- `member-service/src/main/java/com/workernotfound/member/global/exception`

## 2026-06-16 - Initial Member API Authentication

Decision:
- `member-service` directly validates auth-service JWT access tokens for the initial `/api/members/me` flow.
- The JWT secret is provided through configuration and must match auth-service's signing secret.
- Internal member APIs remain protected separately by the `X-Internal-Secret` header.

Reason:
- This removes the temporary `X-Member-Id` external API dependency before an API Gateway exists.
- Direct validation keeps the current local MSA flow testable without adding another service.

Implication for agents:
- Do not rely on client-supplied `X-Member-Id` for external member APIs.
- Do not hardcode JWT secrets in member-service.
- Keep TODOs or extension points for replacing direct validation with API Gateway verified identity propagation later.

Related files:
- `member-service/src/main/java/com/workernotfound/member/global/security`
- `member-service/src/main/java/com/workernotfound/member/domain/member/controller/MemberController.java`

## 2026-06-16 - Initial Flyway Schema Migrations

Decision:
- Add service-owned Flyway `V1__init_schema.sql` migrations for auth-service and member-service.
- Keep local Hibernate `ddl-auto` at `none` by default so schema changes go through Flyway.

Reason:
- Runtime databases should be reproducible from versioned migrations instead of Hibernate auto-DDL.
- Service-owned migrations preserve each service's persistence boundary.

Implication for agents:
- Add new schema changes through new Flyway migration files.
- Do not set local or production `ddl-auto` to `update` as a substitute for migrations.
- Do not modify existing Flyway migrations after they are shared unless the user explicitly approves it.

Related files:
- `auth-service/src/main/resources/db/migration/V1__init_schema.sql`
- `member-service/src/main/resources/db/migration/V1__init_schema.sql`
- `.env.example`

## 2026-06-17 - Auth Flow Safety Hardening

Decision:
- Refresh token reissue must validate that the auth account is `ACTIVE`.
- Refresh token rotation must lock the refresh token row during reissue to reduce concurrent replay risk.
- Verification code sending and verification attempts must be limited with Redis-backed counters.
- If member-service creation succeeds but auth-service persistence fails during signup, auth-service should call a member-service internal compensation endpoint.
- Verification code logging must be disabled by default and enabled only through local configuration.

Reason:
- Blocked or withdrawn accounts must not continue receiving new tokens through refresh token reissue.
- Concurrent refresh token reuse can otherwise mint multiple valid rotated tokens.
- Public verification endpoints need basic abuse protection.
- Cross-service signup can leave orphan member records without compensation.
- Verification codes are sensitive and should not be logged outside local testing.

Implication for agents:
- Preserve account status validation and row locking when changing refresh token rotation.
- Do not remove verification rate/attempt limits without replacing them with equivalent protection.
- Keep verification code logging behind configuration, defaulting to disabled.
- Keep signup compensation behavior or replace it with a stronger consistency mechanism such as idempotency, pending state, or outbox-based cleanup.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/domain/token/service/TokenService.java`
- `auth-service/src/main/java/com/workernotfound/auth/domain/token/repository/RefreshTokenRepository.java`
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service/VerificationService.java`
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service/SignupService.java`
- `member-service/src/main/java/com/workernotfound/member/domain/member/controller/MemberInternalController.java`
- `member-service/src/test/java/com/workernotfound/member/domain/member/service/MemberSignupCompensationTests.java`

## 2026-09-08 - Owner Signup Business Information Verification

Decision:
- OWNER signup requires a store name entered directly by the user.
- member-service verifies the submitted business registration number through the National Tax Service business status API.
- Only taxpayer status code `01` (operating business) is accepted for signup.
- The status lookup uses only the business registration number; representative identity and ownership are not verified.
- Business verification status is computed by member-service and is not accepted from auth-service.

Reason:
- The signup flow needs a store name for later job posting while the public status API does not provide it from a number-only lookup.
- Number-only status lookup provides a lightweight operating-status check without collecting representative identity data.
- Verification results must not be trusted when supplied by a client or another service request.

Implication for agents:
- Do not describe status lookup as representative or ownership verification.
- Keep the store name as user-provided profile data unless a stronger verification policy is explicitly adopted.
- Reject 휴업, 폐업, and unregistered numbers, and fail closed when the external status service is unavailable.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/dto/request/OwnerSignupRequest.java`
- `member-service/src/main/java/com/workernotfound/member/domain/owner`
- `member-service/src/main/java/com/workernotfound/member/external/client/nts`

## 2026-09-09 - Signup Transaction Boundary and OAuth Ticket Lifetime

Decision:
- Service-to-service and external API calls during signup run outside auth-service and member-service database transactions.
- After member-service creates a member, auth-service persists AuthAccount, role-specific authentication data, and RefreshToken in a short local transaction.
- OAuth signup tickets expire 30 minutes after their original issuance time.
- A ticket restored after a retryable owner-signup rejection receives only its remaining original lifetime and is not restored after expiration.

Reason:
- Slow member-service or National Tax Service responses must not hold database connections and transactions open.
- Retrying a rejected signup must not extend the lifetime of an OAuth credential beyond the original security policy.
- Cross-service signup still needs compensation because local transactions cannot roll back data owned by another service.

Implication for agents:
- Do not move remote signup calls into a database transaction.
- Keep auth signup persistence in a separate transactional service or an equivalent explicit transaction boundary.
- Preserve the original OAuth signup ticket expiration when adding retry or restoration behavior.
- Keep member-service compensation when auth persistence fails unless it is replaced with a stronger consistency mechanism.

Related files:
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service/SignupService.java`
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service/SignupPersistenceService.java`
- `auth-service/src/main/java/com/workernotfound/auth/domain/auth/service/OAuthSignupTicketService.java`
- `member-service/src/main/java/com/workernotfound/member/domain/member/service/MemberApplicationService.java`

## 2026-09-09 - CodeRabbit Review Configuration

Decision:
- Keep repository-specific CodeRabbit settings in the root `.coderabbit.yaml` file.
- Use Korean, balanced (`chill`) automatic reviews for non-draft pull requests targeting the default branch.
- Use `AGENTS.md`, `docs/agent/*.md`, `docs/architecture/*.md`, and `docs/PROJECT_CONTEXT.md` as review guidelines.
- Apply focused path instructions for security, external integrations, Flyway migrations, tests, build configuration, and local infrastructure.
- Keep CodeRabbit's request-changes workflow disabled so the existing teammate approval and merge process remains authoritative.

Reason:
- Version-controlled settings make review behavior visible and reviewable with the codebase.
- Existing repository documents already contain the project's architecture, security, coding, and verification rules.
- Focused review guidance reduces low-value style noise while prioritizing high-risk backend changes.

Implication for agents:
- Update `.coderabbit.yaml` when CodeRabbit review behavior or repository structure changes.
- Keep reusable project rules in their owning agent or architecture document instead of duplicating them extensively in path instructions.
- Do not enable automatic approval or replace the required teammate review without an explicit workflow decision.

Related files:
- `.coderabbit.yaml`
- `AGENTS.md`
- `docs/PROJECT_CONTEXT.md`
- `docs/agent/checklists.md`
- `docs/agent/coding-rules.md`

## 2026-09-11 - MVP Feature Scope

Decision:
- Include job posting, application, matching, work, notification, chat, review, trust score, payment, settlement, automatic rematching, and no-show prediction in the planned feature scope.
- Support one business profile per owner in the current scope.
- Exclude multi-business management, administrator systems, reports, appeals, and dispute handling from the current scope.
- Keep ordinary payment cancellation and refund flows in scope.

Reason:
- The product needs the full matching-to-settlement journey and its advanced matching features.
- Multi-business management and operator workflows can be added after the core user journey works end to end.
- Excluding dispute handling does not remove the need to recover ordinary payment cancellation and failure cases.

Implication for agents:
- Use `docs/architecture/mvp-domain-flow.md` as the baseline for domain states and cross-service flows.
- Do not add administrator, report, appeal, dispute, or multi-business features unless a later decision changes the scope.
- Keep policy values that have not been decided configurable and document them before implementation.

Related files:
- `docs/architecture/mvp-domain-flow.md`

## 2026-09-11 - Initial Application Domain Policy

Decision:
- `matching-service` owns applications, application status history, queue source data, and matching score snapshots.
- MySQL application data is the source of truth; Redis is a recoverable queue projection.
- A worker can create only one application for a job posting, including an application that was later canceled.
- Keep an application `APPLIED` while a matching proposal is `PENDING`; change it to `SELECTED` only after matching confirmation completes.
- Use `worker_member_id` with the authentication principal `memberId` as the cross-service worker identifier.
- Do not persist current rank on the application. Store versioned score snapshots and calculate rank from a deterministic order.
- Record unavailable score inputs as missing rather than assigning a fabricated zero. Connect them when their source services and contracts are implemented.

Reason:
- Database uniqueness provides a reliable final guard against concurrent duplicate applications.
- Separating application state from matching attempts avoids reversing application state after a declined or expired proposal.
- Versioned snapshots preserve why a worker received a score while allowing the scoring policy and available inputs to evolve.
- Treating Redis as a projection prevents a partial Redis failure from losing a valid application.

Implication for agents:
- Add a unique constraint on `(job_post_id, worker_member_id)` when implementing applications.
- Do not allow reapplication after cancellation without a new explicit policy and schema change.
- Do not use the stale `user-service.users.id` reference from the first ERD for matching-service implementation.
- Keep score components nullable and record missing inputs until rating, experience, no-show, online-status, and ETA contracts exist.
- Coordinate an application-eligibility contract with `job-service` rather than treating a public job detail read or a stub as an atomic eligibility check.

Related files:
- `docs/architecture/matching-application-design.md`
- `docs/architecture/matching-application-erd.drawio`
- `docs/architecture/mvp-domain-flow.md`
