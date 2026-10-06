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
- CodeRabbit reviews each PR. Assess its findings against the code and the project contracts, apply valid fixes, and verify them before merging.
- After the initial CodeRabbit review and valid fixes, apply the risk-based follow-up review rule recorded on 2026-10-05 before squash-and-merge. A separate teammate approval is not required.
- Use squash-and-merge.
- Delete the branch after the PR is merged.
- For a new unit of work, recreate a fresh branch from the appropriate base branch.

Reason:
- This keeps review responsibility clear and keeps branch history tidy.

Implication for agents:
- Do not merge before CodeRabbit review completion and resolution of valid findings.
- Require an actual initial CodeRabbit review. Apply the 2026-10-05 risk-based rule to later changes instead of requiring coverage of every commit.
- When a follow-up review is required, a rate limit, skipped review, pending review, or successful status check without actual review coverage is not review completion. Wait until the stated reset time before retrying.
- Keep `.coderabbit.yaml` path filters inclusive so changed paths are not excluded by repository configuration.
- Keep the initial PR review automatic, but disable automatic incremental reviews. Batch fixes and verification locally; request `@coderabbitai review` manually when the follow-up changes meet the risk-based criteria.
- Do not request another review while one is in progress or push partial follow-up changes that would supersede it.
- Treat automatic review pause separately from a rate limit. Request a manual review after a pause when an initial or risk-based follow-up review is required.
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
- Verification codes must never be logged by auth-service.

Reason:
- Blocked or withdrawn accounts must not continue receiving new tokens through refresh token reissue.
- Concurrent refresh token reuse can otherwise mint multiple valid rotated tokens.
- Public verification endpoints need basic abuse protection.
- Cross-service signup can leave orphan member records without compensation.
- Verification codes are sensitive and should not be logged outside local testing.

Implication for agents:
- Preserve account status validation and row locking when changing refresh token rotation.
- Do not remove verification rate/attempt limits without replacing them with equivalent protection.
- Never add verification-code logging. Use Gmail SMTP for email and SOLAPI for SMS; SMTP send completion or provider acceptance must be confirmed before returning send success. Configure credentials and registered sender identities in the runtime environment.
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
- Keep CodeRabbit's request-changes workflow disabled. Follow the CodeRabbit review, finding assessment, fix, verification, and squash-merge process confirmed on 2026-09-15.

Reason:
- Version-controlled settings make review behavior visible and reviewable with the codebase.
- Existing repository documents already contain the project's architecture, security, coding, and verification rules.
- Focused review guidance reduces low-value style noise while prioritizing high-risk backend changes.

Implication for agents:
- Update `.coderabbit.yaml` when CodeRabbit review behavior or repository structure changes.
- Keep reusable project rules in their owning agent or architecture document instead of duplicating them extensively in path instructions.
- Do not treat an automatic approval or a successful review check alone as proof that all findings were addressed; assess the actual review comments.

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
- Require `job-service` to issue a short-lived application admission after atomically checking job status and deadline. The admission does not reserve a recruitment seat.
- Use a separate application `revision`, starting at 1, for status-event ordering and keep `version` for optimistic locking.
- Use only `READY` snapshots from a `READY` score batch for ranked queues and automatic matching.

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
- Send `X-Internal-Secret` on internal service calls and fail closed when internal authentication fails.
- Rebuild Redis ranked queues from MySQL application state and the latest completed score batch, including its policy version.

Related files:
- `docs/architecture/matching-application-design.md`
- `docs/architecture/matching-application-erd.drawio`
- `docs/architecture/mvp-domain-flow.md`

## 2026-09-13 - Initial Matching Score Policy

Decision:
- Use `application-time-v1` as the initial score policy while only application time is available.
- Sort current `APPLIED` applications by `applied_at ASC, application_id ASC` and calculate the relative application-time score as `((candidate count - zero-based position) / candidate count) * 100`, rounded to four decimal places.
- Use the application-time score as the initial total score.
- Keep rating, industry experience, online status, expected arrival time, and no-show risk values and component scores `NULL`, and record their names in `missing_inputs`.
- Create a new score batch for every recalculation instead of updating a previous batch or snapshot.

Reason:
- Application time is the only scoring input currently owned by matching-service.
- A relative score preserves deterministic first-come priority without fabricating unavailable values as zero.
- Immutable versioned batches preserve the exact basis of an earlier ranking when future data sources and scoring policies are connected.

Implication for agents:
- Do not replace missing score inputs with zero or silently omit them from the input snapshot.
- Add future scoring inputs through a new policy version and a new batch.
- Keep the final ranked order deterministic with `total_score DESC, applied_at ASC, application_id ASC`.
- Treat urgency weights and the multi-factor formula as a later decision that depends on job, member, work, presence, and routing contracts.

Related files:
- `docs/architecture/matching-application-design.md`
- `matching-service/src/main/java/com/workernotfound/matching/domain/score`

## 2026-09-14 - Manual Matching Candidate Foundation

Decision:
- Treat owner selection as a `MANUAL`, `PENDING` matching attempt, not as final confirmation.
- Allow one matching record per application and preserve the selected `READY` score batch and snapshot when available.
- Keep unavailable score references `NULL`; manual selection does not require a fabricated score.
- Serialize manual selection and worker cancellation with a lock on the application row. If a pending matching exists when the worker cancels, cancel the matching and application in the same transaction.
- Require job-service to atomically reserve recruitment capacity before the later confirmation Saga. Candidate selection itself does not reserve a seat.

Reason:
- Payment, scheduled work, chat, and the job-service capacity contract are not implemented yet, so marking the match confirmed would claim an end-to-end guarantee that does not exist.
- A stable matching record and score basis are needed before those integrations, while the application row provides a shared concurrency boundary for selection and cancellation.

Implication for agents:
- Do not expose a `PENDING` manual matching as a completed hire.
- Add the job-service seat reservation client only when its contract is implemented or the confirmation Saga unit begins.
- Change the application to `SELECTED` only after all confirmation steps complete.

Related files:
- `docs/architecture/matching-application-design.md`
- `matching-service/src/main/java/com/workernotfound/matching/domain/matching`

## 2026-09-14 - Worker Matching Proposal Response

Decision:
- Allow a worker to list and read only their own matching proposals and to decline a `PENDING` proposal.
- Make repeated decline requests idempotent while rejecting decline from any other terminal matching status.
- Keep the application `APPLIED` when a proposal becomes `DECLINED`.
- Use application-row then matching-row lock order for both decline and application cancellation.
- Defer proposal acceptance until the job-service seat reservation and the payment/work/chat confirmation Saga are available.

Reason:
- A decline ends the matching attempt but does not represent withdrawal of the underlying application.
- Consistent lock order prevents a deadlock between matching response and application cancellation.
- Confirmation without recruitment capacity and downstream resource guarantees would create a false completed state.

Implication for agents:
- Do not transition an application to `SELECTED` on proposal lookup or decline.
- Exclude an application that already has a matching record when implementing later automatic candidate extraction, even if its application status remains `APPLIED`.
- Implement acceptance together with the confirmation Saga instead of adding a temporary direct `CONFIRMED` transition.

Related files:
- `docs/architecture/matching-application-design.md`
- `matching-service/src/main/java/com/workernotfound/matching/domain/matching`

## 2026-09-14 - Matching Confirmation Saga Boundary

Decision:
- Let `matching-service` coordinate worker acceptance as a persisted Saga.
- Reserve recruitment capacity before creating payment, scheduled-work, and chat resources, then consume the reservation before committing local `CONFIRMED` and `SELECTED` states.
- Persist one Saga per matching with stable command IDs, external resource IDs, attempt count, and a renewable execution lease.
- Treat network failures, malformed success responses, and 5xx responses as unknown outcomes. Resume the same forward command ID instead of compensating immediately.
- Use separate stable command IDs for forward, confirmation, and compensation steps. Start a new set only after a definitively rejected command has been fully compensated.
- Compensate chat, work, payment, and the recruitment-seat reservation in reverse creation order when confirmation fails before seat consumption.
- Fail closed while the job, payment, work, or chat contract is unavailable instead of exposing a temporary successful confirmation.

Reason:
- Recruitment capacity and downstream resources belong to different services and cannot share a local database transaction.
- Stable idempotency keys and persisted step results allow an interrupted coordinator to resume without duplicating resources.
- A lease prevents concurrent acceptance requests from coordinating the same matching while allowing recovery after a crashed process.

Implication for agents:
- Do not mark a matching `CONFIRMED` or an application `SELECTED` until every Saga step and seat consumption has succeeded.
- Keep external resource IDs as logical references without physical cross-service foreign keys.
- When a source service is added, implement the documented internal contract and idempotency behavior instead of bypassing the Saga.
- A failed compensation must retain the unresolved resource ID and be retried before beginning a new confirmation attempt.
- Do not allow proposal cancellation or decline while an unknown external outcome still needs to be recovered, even when no external resource ID was recorded locally.

Related files:
- `docs/architecture/mvp-domain-flow.md`
- `docs/architecture/matching-application-design.md`
- `docs/architecture/matching-application-erd.drawio`
- `matching-service/src/main/java/com/workernotfound/matching/domain/matching`

## 2026-09-14 - Recruitment Completion Boundary

Decision:
- Let `job-service` decide when recruitment is complete and notify `matching-service` through an authenticated, idempotent internal contract.
- Change every remaining `APPLIED` application for the job to `REJECTED` and cancel its `PENDING` matching proposal in one local transaction.
- Store application and matching status histories and an `ApplicationRejected` Outbox event with those transitions.
- Reject the whole completion command while any affected matching has an unknown Saga outcome or unfinished compensation; the producer retries the same command later.
- Persist the completed job version behind a per-job database lock. Application creation uses the same lock, rejects a late admission from that version, and accepts an admission from a higher reopened version.
- Rebuild the Redis application and score queues once per completed job instead of once per rejected application.

Reason:
- Recruitment capacity and posting status belong to `job-service`, while application and matching states belong to `matching-service`.
- All-or-nothing local processing prevents a job from exposing a mixture of open and rejected candidates.
- Waiting for uncertain Saga work avoids losing a remotely created resource or leaving a consumed seat attached to a locally canceled proposal.

Implication for agents:
- Do not infer recruitment completion from local application counts or reject other applicants after every single confirmation.
- Keep the current REST endpoint as an adapter for the semantic `RecruitmentCompleted` contract; a later broker integration must preserve its idempotency and retry behavior.
- Include the source `jobVersion` in the completion contract so reopen cycles are distinguishable.
- Superseded by "2026-10-04 - Recruitment Completion Producer": the producer is now implemented in `job-service`. Previously: do not implement the producer inside `job-service` unless work in the job domain is explicitly in scope.

## 2026-09-14 - Matching Domain Event Transport

Decision:
- Publish matching-service Outbox events to the untrimmed Redis Stream `matching:domain-events` through a database-leased relay.
- Preserve order per aggregate revision, retry indefinitely with capped exponential backoff, and recover expired leases across service instances.
- Provide at-least-once delivery. Downstream consumers deduplicate by stable `eventId` and reject stale aggregate revisions.
- Require durable, no-eviction Redis for the event Stream. Local Compose uses AOF with `appendfsync always`.

Reason:
- Redis is already an operational dependency of matching-service and Streams provide a durable consumer-group transport without adding another broker for the current scale.
- A short database lease prevents concurrent relays from normally publishing the same row while still allowing recovery after a process crash.
- The publish/mark gap cannot be atomic across MySQL and Redis, so duplicate-safe at-least-once delivery is the honest contract.

Implication for agents:
- Never mark an Outbox row `PUBLISHED` before Redis acknowledges the Stream append.
- Do not trim the Stream until every consumer retention and recovery requirement is defined.
- Keep `eventId` stable across retries and preserve aggregate revision ordering.
- If the transport changes later, retain the publisher port and delivery semantics.

Related files:
- `docs/architecture/matching-application-design.md`
- `docs/architecture/mvp-domain-flow.md`
- `matching-service/src/main/java/com/workernotfound/matching/domain/application`

## 2026-09-15 - Scheduled Work Saga Commands

Decision:
- Implement scheduled work creation and compensation in `work-service` using service-owned MySQL and the existing matching Saga HTTP contract.
- Persist each successful command key, request fingerprint, and result atomically with work state and history. Reject key reuse for a different operation or payload.
- Serialize mutations through a per-matching database row and enforce at most one non-canceled work per matching with a database unique constraint.
- Keep canceled attempts as history. A new Saga attempt uses a new creation key and may create a replacement only after the previous work is canceled.
- Retrying an old creation returns its original work ID, even after cancellation. Repeating compensation for an old work cannot release the replacement's slot.
- Limit the internal compensation endpoint to `SCHEDULED -> CANCELED`. Other lifecycle transitions and user cancellation require their own policy and APIs.

Reason:
- Matching can retry with new forward command IDs after all compensation succeeds; a permanent unique constraint on matching ID would block valid retries.
- Stable responses allow recovery after a committed command loses its HTTP response.
- Work is created before local matching confirmation inside the Saga, and may be canceled if a later step fails.

Implication for agents:
- Do not treat scheduled work creation alone as proof that matching is confirmed or expose check-in before confirmation is safely established.
- Keep command records and canceled work for recovery; do not silently reactivate a canceled work on an old create retry.
- Preserve work-date/time snapshots, including overnight schedules. Do not invent GPS, no-show, or attendance policy in the scheduled-work contract.

Related files:
- `docs/architecture/work-scheduled-design.md`
- `work-service`

## 2026-09-15 - CodeRabbit Review Before Squash Merge

Decision:
- Use CodeRabbit review instead of requiring a teammate review.
- Read review findings, check their validity against code and project contracts, fix valid issues, and run relevant verification.
- Complete the initial CodeRabbit review, resolve valid findings, and apply the 2026-10-05 risk-based follow-up review rule before squash-and-merge. Delete the work branch after merge.

Reason:
- The user explicitly clarified the repository's intended GitHub workflow.

Implication for agents:
- Follow issue → branch from develop → implementation and verification → commit/push → PR to develop → CodeRabbit review → valid fixes and verification → squash merge.
- Do not stop at PR creation when the user has authorized completion of this workflow.
- Do not require a separate teammate approval or blindly apply every automated suggestion.

Related files:
- `docs/agent/checklists.md`
- `.coderabbit.yaml`

## 2026-09-15 - Chat Room Saga Commands

Decision:
- Implement the existing matching Saga create/close contract in chat-service with service-owned MySQL.
- Persist command results and room history atomically, serialize by matching, and allow at most one OPEN room per matching.
- Retain CLOSED rooms and return the original result for old command retries. A new creation key may create a replacement after closure; late closure must not affect it.
- Treat OPEN as internal provisioning only. Add confirmation and participant authorization before exposing user messaging.
- Use BusinessException with domain codes for expected failures; do not classify arbitrary IllegalArgumentException as a client error in the new service.

Reason:
- Saga retries and compensation must not duplicate rooms or close a replacement, and unexpected server failures must remain distinguishable from definitive command rejection.

Implication for agents:
- Preserve the idempotency fingerprint format for existing commands when evolving request schemas.
- Keep messaging, WebSocket, user room access, and confirmation consumption as separate follow-up work.

Related files:
- `docs/architecture/chat-room-design.md`
- `docs/architecture/error-handling-review.md`
- `chat-service`

## 2026-09-15 - Existing Service Error Boundaries

Decision:
- Use domain-coded business exceptions for member/signup/token/application/matching rejections and retain internal invariant/infrastructure failures as server errors.
- Member lookup failures use 404, duplicate checks use 409, and verification resend limits use 429. Auth forwards known member/worker errors only when both status and code match its whitelist.
- Parse JWTs once in authentication filters and distinguish invalid credentials from signing-engine failures. Preserve anonymous access policies for invalid credentials; explicitly route engine failures to safe 500 responses.
- Preserve standard MVC protocol statuses and headers and sanitize binding, external API, and unexpected server-error responses in all six services.
- Keep matching Saga remote metadata and unknown-outcome recovery semantics unchanged.

Reason:
- Generic 400 and false validation results hid business meaning and server failures, while early remote-error flattening could lead to incorrect retry or compensation decisions.

Implication for agents:
- Follow `docs/architecture/error-handling-review.md` for public error-code changes and remaining internal guards.
- Do not globally convert unknown DB constraint violations to duplicates or network failures to definitive rejections.
- Record safe exception types/locations rather than raw sensitive exception messages.


## 2026-09-21 - Signup Database Conflict Translation

- Translate only `uk_auth_accounts_email` and `uk_oauth_connections_provider_user` violations to the corresponding auth 409 business errors.
- Translate in `SignupService`, outside the persistence transaction, after rollback and the existing member compensation attempt. This also covers commit-time failures.
- Retain the original cause and suppressed compensation failures; unrelated integrity violations remain server errors.


## 2026-09-21 - Deposit-backed Payment Saga Commands

Decision:
- Implement the existing matching lock/release contract with payment-service-owned MySQL and per-job deposit balances.
- Serialize by command, matching, then deposit; prevent overspending across different matches and permit one active lock per matching.
- Retain successful and definitively rejected command outcomes. A rejected command must not become successful later when funds become available; a new attempt uses a new key.
- Keep released attempts and return original IDs for old create retries. Late release cannot change a replacement lock or return funds twice.
- Treat RELEASED as the lock lifecycle, separate from PG refund and the broader payment state model. Lock release restores available deposited funds.
- Do not fabricate deposited funds. Provider choice, deposit timing, verified payment ingestion, and real PG integration remain pending; tests alone insert funding fixtures.

Reason:
- The matching Saga already expects stable payment IDs and reverse compensation, while multiple workers can consume one job's funding.
- A definitive rejection must remain definitive across delayed retries so compensation cannot be followed by an orphaned payment lock.

Implication for agents:
- Provider integration must verify server-owned order, amount, currency and successful payment before atomically crediting a deduplicated deposit ledger.
- Keep business rejection checks before balance mutation; unexpected persistence errors roll back instead of committing a rejection.
- Do not equate successful fixture-based tests with verified deposit ingestion or end-to-end matching acceptance.

Related files:
- `docs/architecture/payment-lock-design.md`
- `payment-service`


## 2026-09-22 - Toss Test Deposits Before Job Publication

Decision:
- Use Toss Payments directly in the test environment, initially card payments and integer KRW only. Reject live secret keys in this implementation.
- Create jobs privately in PAYMENT_PENDING and open them only after verified funding. The job domain computes the total expected wage and owns publication; payment-service accepts trusted internal order snapshots, not browser-supplied pricing.
- Keep order amount, owner and job version immutable. Replace only unbound READY or verified FAILED orders; uncertain or funded orders cannot be replaced for a new charge.
- Authenticate owner approval and query with the existing JWT identity contract. Persist the provider payment key before approval and recover unknown outcomes with the same order/key rather than making another charge.
- Treat webhook bodies as hints; re-query known bound keys with provider authentication before changing funds. Commit deposit credit, history and durable job notification atomically.
- Fence job funding notifications by order and funding revision. A delayed older acknowledgement cannot clear a newer notification, and the job receiver must reject stale revisions.
- Block new matching locks on verified cancellation or partial cancellation; keep existing allocated balances for later refund/settlement handling.

Reason:
- An unfunded public job can attract applicants but fail at acceptance. Stable orders and verified payment facts prevent browser amount tampering, duplicate credits and orphaned publication updates.

Implementation boundary:
- job-service is owned by a teammate and still needs private creation, order provisioning and funding-status consumption. No job implementation was changed in this unit.
- Actual test checkout requires private test keys and frontend authentication. Test deposits and internal allocation are not production escrow or worker payouts.

Related files:
- `docs/architecture/toss-deposit-design.md`
- `payment-service`


## 2026-09-22 - Work Confirmation and Participant Queries

Decision:
- Consume matching `MatchConfirmed` v1 to record work confirmation separately from scheduled-work provisioning. Keep work lifecycle status `SCHEDULED` until future attendance commands.
- Validate the event envelope and the stored work snapshot, serialize with existing matching/work locks, and persist the event receipt and confirmation in one transaction before Redis ACK.
- Use the dedicated `work-confirmation-v1` group with one shared logical consumer across replicas. Scan pending records with a rotating cursor and independently read new records; MySQL makes concurrent redelivery safe.
- Expose only confirmed participant-owned work through JWT-authenticated list/detail APIs. Do not infer confirmation for existing rows or expose payment IDs.
- Do not revive canceled attempts, apply stale revisions, or allow Saga compensation after confirmation. User cancellation remains a separate future contract.

Reason:
- Scheduled work is provisioned before the confirmation Saga completes. Creation alone must not grant user access or future attendance eligibility.
- At-least-once delivery needs durable duplicate handling and recovery after a committed update loses its acknowledgment.

Implication for agents:
- Do not infer confirmation from `SCHEDULED` status or scheduled-work creation alone.
- Persist the confirmation update and event receipt in one transaction, and acknowledge only after commit.
- Validate the confirmation event envelope and stored work snapshot before applying or acknowledging it.
- Keep participant queries limited to the authenticated member's confirmed work and do not expose payment identifiers.
- Preserve duplicate and stale-event handling; do not revive canceled attempts or allow Saga compensation after confirmation.

Related files:
- `docs/architecture/work-scheduled-design.md`
- `work-service`

## 2026-10-02 - Chat Confirmation and Participant Queries

Decision:
- Consume matching `MatchConfirmed` v1 to record chat confirmation separately from internal room provisioning. `OPEN` alone does not grant user access.
- Validate the event envelope and stored room snapshot, serialize with the existing matching slot and room locks, and persist the event receipt and confirmation in one transaction before Redis ACK.
- Calculate `MatchConfirmed` v1 receipt fingerprints from its fixed field set in order, using ISO local date-time and length-prefixed values. Do not use the consumer record's `toString()`, since new record fields must not change prior event fingerprints.
- Use the dedicated `chat-confirmation-v1` group and one shared logical consumer across replicas. Recover pending records before reading new records; retain failed deliveries for retry.
- Expose only confirmed OPEN rooms to their authenticated owner or worker through list/detail APIs. Do not infer confirmation for old rooms.
- Reject Saga compensation closure after confirmation. A closed old attempt cannot confirm or affect a replacement room.

Reason:
- Chat rooms are provisioned before matching confirmation finishes. Early user access would expose a room for a match that may still be compensated.
- At-least-once delivery requires durable duplicate handling and recovery after a committed update loses its acknowledgment.

Related files:
- `docs/architecture/chat-room-design.md`
- `chat-service`

## 2026-10-02 - Shared JWT Signing Secret Minimum

Decision:
- Require the shared JWT secret used by auth-service and chat-service to be nonblank and at least 32 UTF-8 bytes. Reject invalid configuration at startup.

Reason:
- A missing or short HMAC key must not reach token issuance or validation at request time. Both services use the same `AUTH_JWT_SECRET` setting.

Related files:
- `.env.example`
- `auth-service`
- `chat-service`


## 2026-10-03 - Job Admission Idempotency Conflict Translation

Decision:
- Translate only `uk_job_application_admissions_idempotency_key` violations to the `JOB-409-004` business error.
- Translate in `GlobalExceptionHandler` at the global HTTP exception boundary, after the admission transaction has rolled back. This also covers commit-time failures; application and command services propagate persistence exceptions unchanged.
- Walk the whole cause chain to find the Hibernate `ConstraintViolationException`, and compare the exact constraint name without its table prefix. Continue past unrelated constraints and stop safely on cycles. Preserve the original exception; unrelated integrity violations remain server errors.

Reason:
- The admission transaction locks only the job post row. Concurrent requests that reuse one idempotency key across different job posts lock different rows, so they are not serialized and only the unique constraint rejects them.
- Sequential reuse already returns `JOB-409-004`, so the concurrent case must not return a 500 for the same contract violation.

Implication for agents:
- Follow this pattern for the seat reservation idempotency constraints in later job-service work.
- Do not convert integrity violations inside a transaction, and do not convert them without matching the constraint name.

Related files:
- `docs/architecture/job-post-design.md`
- `job-service/src/main/java/com/workernotfound/job/global/exception/GlobalExceptionHandler.java`
- `job-service/src/test/java/com/workernotfound/job/domain/job/service/JobApplicationAdmissionConstraintErrorTests.java`

## 2026-10-04 - Job Application Admission TTL

Decision:
- Keep the initial application-admission TTL at five minutes. Configure it with `job.application-admission.ttl`, backed by `APPLICATION_ADMISSION_TTL` with a `5m` default.
- Calculate `expiresAt` as `admittedAt + ttl` when issuing an admission. A configuration change applies to newly issued admissions; existing admissions retain their persisted expiry.
- A retry of the same job, worker and idempotency key returns the existing unexpired `RESERVED` admission without extending its expiry. Expired admissions return `JOB-409-003`; a new key must pass the current job status and deadline checks again.

Reason:
- Admission issuance and matching-service application persistence are separate operations. A short validity window gives internal calls and retries room to recover from transient latency or failures.
- Five minutes is an initial operational tradeoff: it permits short recovery attempts while limiting how long an unused approval can be reused after the job changes or closes. It is not a measured latency guarantee and should be revisited using observed application-save and retry durations.
- An admission does not reserve a recruitment seat. Its TTL limits approval reuse; it does not extend the job's application deadline for new admissions or guarantee a matching slot.

Implication for agents:
- Keep the duration configurable and preserve the original expiry on idempotent retries.
- Verify both orderings of job closure and admission issuance against the same MySQL job-row lock. The current race tests simulate closure in a separate transaction because the production status-transition API is not implemented yet; connect that API to these scenarios when it is added.

Related files:
- `job-service/src/main/resources/application.yaml`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/ApplicationAdmissionProperties.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobApplicationAdmissionCommandService.java`
- `job-service/src/test/java/com/workernotfound/job/domain/job/service/JobApplicationAdmissionClosingRaceTests.java`

## 2026-10-04 - Matching Seat Reservation and Expected Wage

Decision:
- job-service reserves recruitment seats only for `OPEN` or `MATCHING` jobs and only before work start (`workDate + startTime`). The application deadline is not the seat-reservation deadline.
- Seat reservations expire `MATCHING_SEAT_RESERVATION_TTL` (default 10 minutes) after issuance. This is separate from the application-admission TTL and is never extended by retries.
- Reserve, confirm, release, and expiry recovery lock the job row before the reservation row so `CONSUMED + valid RESERVED <= recruitCount` always holds. Expiry recovery updates by primary key only, never by a secondary-index range `UPDATE`, to avoid gap-lock deadlocks with reservation inserts on other jobs.
- A repeated reservation key returns the stored snapshot regardless of status. Confirm and release record only the first successful command key; a different key on an already processed reservation is a conflict. Confirm retries on `CONSUMED` succeed even after the original expiry, and release of `EXPIRED` succeeds.
- The per-worker expected wage is floor(work minutes × (`baseHourlyWage` + `extraWage`) / 60) in integer KRW. `extraWage` is an hourly addition, and null means 0. Break time is not deducted. The total deposit is the per-worker amount × `recruitCount`.
- Reservation timestamps are truncated to microseconds before storage to match `DATETIME(6)`: `reservedAt` = truncated issue time, and `expiresAt` = truncated `reservedAt + ttl`. A TTL with no whole microsecond after truncation is rejected at startup. Expiry stays `expiresAt <= now`. Java comparisons use the untruncated clock, and SQL expiry parameters are truncated so MySQL rounding cannot move the boundary.
- matching-service treats a well-formed, already expired seat-reservation response as a definitive rejection: it compensates that reservation and starts a new attempt. Unknown outcomes are not compensated.
- `endTimeNextDay` travels from the job seat snapshot through the matching Saga to scheduled-work creation. It must equal `endTime <= startTime`. work-service infers it from the times when a request omits it or sends null, so retries of older-format commands still reach the stored command instead of failing with 400. The creation fingerprint keeps the pre-field format.

Reason:
- The user confirmed the 10-minute TTL, the hourly `extraWage`, and the work-start cutoff on 2026-10-04.
- An expired reservation replayed with the same key could otherwise trap the Saga in endless same-key retries.

Implication for agents:
- Keep wage calculation in `JobWageCalculator`; do not duplicate it in payment-service. Record break-time, time-band premium, and urgency premium policies as new decisions before changing the formula.
- Do not treat a seat reservation as recruitment completion.
- Superseded by "2026-10-04 - Recruitment Completion Producer": the confirmation that consumes the last seat now closes the job and stores the completion command in the same transaction. Previously: seat confirmation was not recruitment completion, and the job status transition and completion notification were separate work.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/matching-application-design.md`
- `docs/architecture/work-scheduled-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/MatchingSeatReservationCommandService.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobWageCalculator.java`

## 2026-10-04 - Recruitment Completion Producer

Decision:
- job-service completes recruitment when `CONSUMED` seats reach `recruitCount`. Valid `RESERVED` seats only block further reservations and never count toward completion.
- The last seat confirmation, the `OPEN`/`MATCHING -> CLOSED` transition, the `job_status_histories` row, and the completion command are stored in one local transaction under the existing job-row -> reservation-row lock order. Any failure rolls back the seat confirmation as well.
- The notification `jobVersion` is the job version after the completion transition. It is obtained by flushing the `@Version` increment inside the same transaction, stored once with a UUID command ID, and never re-read from the current job on retries. `(job_post_id, job_version)` is unique.
- With no actor column, `job_status_histories.reason` records `actor:reason`, currently `SYSTEM:RECRUITMENT_FILLED`.
- Delivery uses a database lease: claim in a short transaction, call matching-service outside any transaction, and record the result only when the lease token still matches. An after-commit trigger is an optimization; a scheduler guarantees retries.
- Commands have no terminal failure state. 409, timeouts, network errors, 429, and 5xx retry with capped exponential backoff. Authentication and contract failures stay `PENDING`, retry at the maximum delay, and log errors for operators.
- A bounded periodic reconciler closes jobs whose seats were all consumed before this implementation, using the same lock and criteria.

Reason:
- The matching receiver uses the completion version as its late-application barrier and treats a repeated version as success, so a stable command and version make every retry and lost response converge.
- The matching Saga that confirmed the last seat may still be finishing locally, so the first notification is expected to receive 409.

Implication for agents:
- Do not count `RESERVED` seats as completion, and do not commit the job separately to obtain the version.
- Do not delete commands or mark them successful after retries; do not store response bodies, headers, or secrets.
- Future close or reopen transitions must increment the job version and write `job_status_histories` in the same way.
- Make each scheduler bean conditional on its own property, because `@EnableScheduling` from one scheduler enables every `@Scheduled` bean.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/matching-application-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobRecruitmentCompletionService.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/RecruitmentCompletionDispatcher.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/RecruitmentCompletionReconciler.java`

## 2026-10-04 - Recruitment Completion Call Deadline

Decision:
- Enforce a total deadline (`MATCHING_SERVICE_CALL_TIMEOUT`, default 8s) on each recruitment-completion call, from connection start through the end of the response body. Connect and read timeouts remain separate limits for connection setup and response headers, and may not exceed the total.
- Send this call with the JDK `java.net.http.HttpClient` (HTTP/1.1) as one asynchronous exchange including a bounded body subscriber. When the deadline passes, cancel the exchange so the connection is closed, and record `TIMEOUT`.
- `maxCallDuration()` returns this enforced deadline. The lease must be at least the deadline plus one second; otherwise startup fails.
- Accept timeouts only from 1ms to 1h; reject null, zero, negative, sub-millisecond, and larger values at startup.

Reason:
- `HttpURLConnection` read timeouts bound only the gap between reads. A slow body ran for over 10 seconds with a calculated 400ms limit, and on JDK 17 `disconnect()` from another thread did not close the socket during body reads.
- Cancelling a JDK `HttpClient` exchange closed the connection before headers, during header and body drip, and after a partial body, without accumulating threads. No new dependency is needed.

Implication for agents:
- Do not treat per-read timeouts or `Future.get(timeout)` alone as a total call limit; verify that the connection is actually closed with a real-socket test.
- Keep the lease validation tied to the enforced deadline when changing the HTTP client.
- Body transfer failures and JSON format errors are classified separately; see "Recruitment Completion Response Classification".

Related files:
- `docs/architecture/job-post-design.md`
- `job-service/src/main/java/com/workernotfound/job/external/client/matching/MatchingRecruitmentCompletionClient.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/RecruitmentCompletionDispatcher.java`

## 2026-10-05 - Recruitment Completion Response Classification

Decision:
- Treat failures while receiving the response body as transport failures: a timeout is `TIMEOUT`, and a lost connection or a body shorter than declared is `NETWORK`. They take precedence over an already received HTTP status, which is kept only as diagnostic data.
- Classify a fully received response by HTTP status as before. An empty, malformed, trailing-content, non-boolean `success`, or over-8KB body is never a success; its remote error code is discarded, but the status classification remains (for example, 503 with invalid JSON is `SERVER_ERROR`).
- Do not convert unexpected runtime exceptions into communication or contract failures. They surface at the dispatcher boundary, are logged at error level with only the command ID and exception type chain, and the command is retried after its lease expires.

Reason:
- A body read failure turned into a missing body and was classified as `CONTRACT`, which is non-transient and delayed the first retry from the base delay to the maximum delay.

Implication for agents:
- Do not catch all `IOException` or `RuntimeException` around response parsing; JSON format errors and transport errors have different meanings.

Related files:
- `docs/architecture/job-post-design.md`
- `job-service/src/main/java/com/workernotfound/job/external/client/matching/MatchingRecruitmentCompletionClient.java`

## 2026-10-05 - Safe Recruitment Completion Error Logs and Internal Secret Format

Decision:
- At the recruitment-completion execution boundaries (scheduler dispatch and after-commit dispatch), log unexpected exceptions with only the command ID and the exception class chain. Do not pass the original exception, its message, causes, suppressed exceptions, or stack trace to the logger.
- In job-service, require `job.internal.secret` to be non-empty visible ASCII (0x21-0x7E) and reject other values at startup without trimming or including the value in error messages. `InternalApiProperties.toString()` masks the value.

Reason:
- The JDK HTTP client includes the entire header value in its `IllegalArgumentException` message, so logging the raw exception exposed the internal secret when the configured value contained a newline.
- The same value is sent as an HTTP header and compared as UTF-8 bytes by receivers, so whitespace, control, and non-ASCII characters either cannot be sent or fail comparison.

Implication for agents:
- Do not log raw exceptions at boundaries where unvalidated strings such as request headers can appear in exception messages; log identifiers and exception types instead.
- Keep error messages and failure reports for secret settings free of the rejected value; verify with captured startup output.

Related files:
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/ExceptionTypeChain.java`
- `job-service/src/main/java/com/workernotfound/job/global/security/InternalApiProperties.java`
- `docs/architecture/job-post-design.md`

## 2026-10-04 - Participant Text Messages

Decision:
- Permit text message sends and history reads only for JWT-authenticated participants of confirmed OPEN rooms.
- Scope client message keys to room and sender, compare keys case-sensitively, return the original message for identical retries, and reject changed content with 409.
- Serialize sends with the room row lock before checking duplicates and inserting. Keep a database unique constraint as a final guard and use message IDs as the descending history cursor.
- Preserve text exactly; accept nonblank content up to 2000 UTF-16 code units and ASCII letter/digit/underscore/hyphen keys of 1–128 characters.

Reason:
- A lost response must not duplicate messages. Serializing inserts within each room keeps message-ID order consistent with commit order for pagination and later synchronization.

Related files:
- `docs/architecture/chat-room-design.md`
- `chat-service`


## 2026-10-04 - Chat Notifications and Recovery

Decision:
- Keep REST as the authenticated, idempotent text-message command. Use STOMP over native WebSocket only for room notification subscriptions.
- Verify the existing access token on CONNECT, authorize exact room destinations, reject client SEND and wildcard subscriptions, and block notification delivery after token expiry.
- Publish only room/message IDs through Redis Pub/Sub after the message transaction commits. This channel is best-effort and does not replace persisted messages or the matching domain-event Stream.
- Restore missed messages through an ascending exclusive message-ID REST cursor. Clients reconcile after subscription, reconnect, notifications, and periodically while viewing a room; notification IDs alone must never advance the synchronization cursor.

Reason:
- Persisted messages already provide the recovery source. Lightweight cross-instance hints avoid adding another durable queue, while cursor synchronization handles drops and reordered notifications.

Related files:
- `docs/architecture/chat-room-design.md`
- `chat-service`

## 2026-10-04 - Matching Result In-app Notifications

Decision:
- Add the planned notification-service with service-owned MySQL and a dedicated consumer group on the existing matching event Stream.
- Initially consume MatchConfirmed v1 for OWNER and WORKER, and ApplicationRejected v1 for WORKER. Do not infer missing owner IDs for other application events.
- Commit a stable event fingerprint and recipient notifications atomically before Redis acknowledgment. Preserve failed deliveries for retry and rotate pending reads so malformed messages do not starve newer events.
- Scope notification list, unread count and idempotent read commands to both authenticated member ID and role. Keep event/payment identifiers and other participant IDs out of API responses.
- Treat notifications as event history, not a latest-state projection. Keep source event IDs for deduplication and preserve the first read timestamp.

Reason:
- Matching outcomes need a durable user-visible record even when the user is offline. Read state and redelivery must remain independent, and user privacy must hold across role boundaries.

Related files:
- `docs/architecture/notification-design.md`
- `notification-service`

## 2026-10-04 - Configurable Attendance and Work Completion

Decision:
- Apply the user-approved recommended default: worker GPS check-in within 100 meters and start ±30 minutes; owner confirms work start and completion. Default completion requires scheduled end; overnight work ends the next day.
- Configure radius, windows, time zone, completion role (OWNER/WORKER), and early completion through `work.attendance`. Expose active policy settings and select a replacement `AttendancePolicy` Bean with `WORK_ATTENDANCE_POLICY_TYPE=custom` without changing transaction or participant authorization logic. Explicit selection avoids configuration-registration-order dependence.
- Use job location snapshots carried through the matching Saga. Accept legacy null coordinate pairs for compatibility but reject GPS attendance without a location; never infer or fabricate coordinates.
- Lock confirmed participant-owned work and atomically persist each lifecycle transition, first timestamp and actor history. Repeated commands preserve the first result and do not regress status.
- Keep legacy scheduled-work command fingerprints stable when adding coordinates. Do not produce settlement or work lifecycle events in this unit.

Reason:
- The user asked for recommended policy now and easy replacement later. Defaults must not become hardcoded business constraints, and concurrency/retry protection must survive policy changes.

Related files:
- `docs/architecture/work-scheduled-design.md`
- `docs/architecture/matching-application-design.md`
- `work-service`
- `matching-service`


## 2026-10-04 - Durable Work Lifecycle Events

Decision:
- Store WorkCheckedIn, WorkStarted and WorkCompleted v1 in a work-owned Outbox atomically with status and actor history. The current one-way lifecycle uses revisions 1, 2 and 3 per work.
- Publish to the separate `work:domain-events` Redis Stream after committing a fenced lease. Retry with the same event ID and payload after failures or lease expiry.
- Treat delivery as at least once and potentially out of order across replicas. Consumers deduplicate event IDs and use revisions for state projections; event-history consumers retain each distinct event.
- Pause relay independently from recording, retain pending failures, and never infer historical events during migration.
- Exclude GPS and payment identifiers from payloads. WorkCompleted reports lifecycle completion; financial settlement and downstream consumers require separate implementations.

Reason:
- A successful attendance transaction must not lose its downstream notification when Redis is unavailable or a process stops between publish and acknowledgment.

Related files:
- `docs/architecture/work-scheduled-design.md`
- `work-service`

## 2026-10-04 - Participant Work Notifications

Decision:
- Consume work lifecycle v1 from a separate Redis Stream/group and notify the opposite role: check-in to owner, start to worker, completion to the participant other than the declared actor role.
- Require actor role and member ID together, validate the full envelope and state/revision contract, and commit event receipt plus notification before ACK.
- Preserve each distinct event as notification history even when delivery is out of order. Keep the current work state owned by work-service.
- Add nullable workId to inbox responses and allow applicationId to be absent for work notices. Preserve the existing matching-event v1 fingerprints and member/role access checks.

Reason:
- Users need to see the other participant's work actions, and configurable completion authority must not depend on guessing a role from a member ID.

Related files:
- `docs/architecture/notification-design.md`
- `notification-service`

## 2026-10-05 - Risk-Based CodeRabbit Follow-Up Reviews

Decision:
- Keep one completed CodeRabbit review for every PR. A review of every subsequent commit is not required.
- Request a follow-up review when post-review changes introduce nontrivial behavior or invariants in security, payments, concurrency, data integrity, transaction boundaries, or external integrations, or when their impact is unclear. The commit containing those high-risk changes must then be actually reviewed before merge.
- Do not require another review for documentation, tests, formatting, or a narrow implementation of an already reviewed suggestion that adds no independent behavior. Review those changes directly and run relevant verification.
- Batch related fixes before requesting any follow-up review. A later low-risk change alone does not invalidate a completed high-risk follow-up review.

Reason:
- Automatic incremental reviews are disabled and included reviews are rate-limited. Requiring a new review after every small fix delays delivery without proportionate benefit.
- Changes such as atomic verification-code replacement and expiry restoration can introduce new failure or concurrency behavior, so they warrant another review.

Implication for agents:
- Inspect the actual post-review diff, state the risk assessment and verification in the final report, and request a manual review only when the criteria above apply.
- When a follow-up review is required, do not treat a green check with a skipped, rate-limited, or pending review as coverage. Wait for capacity and confirm the reviewed commit includes the high-risk changes.
- Resolve valid findings and repeat a follow-up review only if another high-risk change is made.

Related files:
- `docs/agent/checklists.md`
- `.coderabbit.yaml`

## 2026-10-05 - Job Private Creation and Payment Order Provisioning

Decision:
- New jobs are stored as `PAYMENT_PENDING` and a payment-order creation command is stored in the same local transaction. Only the authenticated owner (JWT `memberId`) can read a `PAYMENT_PENDING` detail; others get 404. Search, new application admissions, and new seat reservations exclude it; idempotent replays of already issued admissions and reservations keep their contracts.
- The command keeps an immutable snapshot: job ID, payment job version (the version right after the job insert), owner ID, total deposit in integer KRW, `KRW`, and a UUID `Idempotency-Key`. Retries resend the same row. A new order (re-payment, amount edit) must issue a new command with the next per-job `issue_sequence` under the job-row lock; `(job_post_id, issue_sequence)`, the key, and `order_id` are unique.
- Totals below 100 KRW are rejected with `JOB-400-004` and never rounded up.
- Delivery reuses the recruitment-completion lease pattern in separate classes (no generic framework): claim, call outside transactions with a total `PAYMENT_SERVICE_CALL_TIMEOUT`, record only with the matching lease token. Lease must be at least the call timeout plus one second.
- Success requires 2xx, boolean `success=true`, object `data`, required fields, the same job ID/version/amount/currency as the request (response decimals parsed as `BigDecimal` by the client reader, never through double), and an order status of `READY`, `CONFIRMING`, `DEPOSITED`, `FAILED`, or `REVIEW_REQUIRED` (idempotent replays may return a progressed status). `SUPERSEDED` is not linked.
- Timeouts, network errors, 408, 429, and 5xx retry with backoff. Authentication, 409, other 4xx, contract violations, snapshot mismatches, and superseded orders stay `PENDING` at the maximum delay with operator error logs. Commands are never deleted or marked successful on failure.
- Linking locks the job row then the command row, requires the lease token and the latest issue sequence (read with a locking `FOR SHARE` query after the job lock, never a plain read that may reuse an earlier REPEATABLE READ snapshot), marks the command `SUCCEEDED`, and stores `payment_order_id` plus the original payment snapshot on the job. An older command is closed as `SUPERSEDED` without linking. Order creation never changes job status.
- Jobs created before V10 are not changed to `PAYMENT_PENDING` and get no retroactive order. They are not deposit-backed, so matching acceptance fails at the payment-lock step until they are re-created or a later re-payment path provisions an order.

Reason:
- Payment-before-publication was decided on 2026-09-22; payment-service accepts only trusted job snapshots with stable idempotency keys.
- A response lost after payment-service created an order must converge to the original order ID, and a late or older result must not overwrite the latest link.
- Reverting published jobs to private would abruptly block in-progress applications and matching.

Implication for agents:
- Do not publish a job on order creation or fabricate funding. Superseded by "2026-10-05 - Job Funding Status Consumption and Publication": `funding-status` consumption and `PAYMENT_PENDING -> OPEN` are now implemented and compare against the linked order and `payment_job_version`.
- Do not re-read the current job version when retrying; use the stored command snapshot.
- Do not log request headers, the internal secret, idempotency keys, response bodies, or raw exception messages for this call.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/toss-deposit-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/PaymentOrderDispatcher.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/PaymentOrderCommandTransactionService.java`
- `job-service/src/main/java/com/workernotfound/job/external/client/payment/PaymentOrderClient.java`
- `job-service/src/main/resources/db/migration/V10__create_job_payment_order_commands.sql`

## 2026-10-05 - Job Funding Status Consumption and Publication

Decision:
- job-service receives payment-service funding status at `POST /api/jobs/internal/{jobPostId}/funding-status` and persists only verified notifications as receipts (unique `Idempotency-Key`, unique `(order_id, funding_revision)`, full request fields and the original response). Replays of the same key or the same order/revision are checked under the job-row lock before any version or status check and return the stored response. Key reuse for another job or content is `JOB-409-004`; the same order/revision with different content is `JOB-409-012`. Concurrent unique-constraint violations are translated by name in `GlobalExceptionHandler` after rollback.
- Verification compares the order ID, payment job version (never the current `@Version`), owner, integer KRW amount (scale-insensitive), and currency against the linked order snapshot, without recalculating the amount. A notification whose order matches an earlier SUCCEEDED command of the same job returns 200 `STALE_ORDER` without changing state. Other orders and mismatches return `JOB-409-011` without recording.
- A notification that arrives before order linking (latest command still `PENDING` with the same snapshot) gets a retryable 409 `JOB-409-013` and is not recorded (option B). payment-service retries every non-success response with the same key indefinitely, and job-service's order command recovers the original order ID with the same key, so the same command is processed after linking. Notifications never link an order.
- The last applied revision is stored per order. Lower or equal revisions return 200 `STALE_REVISION`. Receipt, revision, block flag, `PAYMENT_PENDING -> OPEN`, and `job_status_histories` (`SYSTEM:FUNDING_CONFIRMED`) commit in one local transaction under the existing job-row lock order.
- `funded=true` clears the funding block and publishes only a `PAYMENT_PENDING` job before its application deadline and work start (`now` equal to a boundary counts as passed). `OPEN`/`MATCHING` return `FUNDING_CONFIRMED` without history. `CLOSED`, deadline-passed, or work-started jobs are not published, return 200 `PUBLICATION_SKIPPED` with a reason, and are flagged `refund_review_required`; no refund is executed.
- `funded=false` sets `job_posts.funding_blocked` without changing the job status. New admissions return `JOB-409-001` and new seat reservations and first `RESERVED -> CONSUMED` confirmations return `JOB-409-005`, both checked under the job-row lock. Idempotent replays, `CONSUMED` seats, and seat release are unaffected. Search excludes blocked jobs; detail visibility is unchanged.
- Field-level strict JSON deserialization rejects non-integer versions/IDs/revisions and non-boolean `funded`. The global Jackson configuration is not changed.

Reason:
- The receiver must converge under duplicate, delayed, and reordered delivery, and a response lost before order linking must not drop or fabricate a funding fact.
- Reverting status to `PAYMENT_PENDING` on cancellation could later reopen a closed job, so the block is a separate flag.
- Existing matching-service mappings already treat `JOB-409-001` as "not accepting applications" and any 4xx confirmation rejection as a definitive rejection that triggers compensation. A new admission code would surface as a 503 dependency failure.
- Option B reuses the two existing unlimited retry paths instead of adding a parked-notification table and a recovery scheduler.

Implication for agents:
- Do not record or acknowledge a link-pending notification as applied, and do not link an order from notification content.
- Do not compare funding notifications with the current `@Version` or the current wage calculation.
- Check the funding block under the job-row lock for every new admission, reservation, and first confirmation; keep idempotent replays ahead of that check.
- Refund review tooling, actual refunds and settlement, and post-confirmation recovery after a block remain follow-up work. Re-payment and a cross-service E2E with a local PG double were added by "2026-10-06 - Job Payment Terms Change and Re-payment"; a real Toss checkout E2E remains.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/toss-deposit-design.md`
- `docs/architecture/matching-application-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobFundingStatusCommandService.java`
- `job-service/src/main/resources/db/migration/V11__create_job_funding_status.sql`

## 2026-10-05 - Email-Code Password Reset

Decision:
- Recover only ACTIVE accounts that already have LOCAL credentials using a PASSWORD_RESET email code and new password submitted together. Do not create LOCAL credentials for OAuth-only accounts or use a shared email verified flag as reset authorization.
- Keep reset codes valid for five minutes, allow five failed attempts, and atomically consume a successful code once in Redis.
- Apply the same one-minute send rate limit to every email before account eligibility lookup. Perform BCrypt encoding only after successful reset-code consumption.
- Change the BCrypt password and passwordChangedAt and revoke every device's refresh tokens in one auth DB transaction. Do not auto-login after reset; existing stateless access tokens retain their expiration.
- Serialize LOCAL login, token issuance/reissue, and reset with the auth account row lock. Acquire the account lock before any refresh-token lock.
- If DB persistence fails after code consumption, roll back DB changes and require a new code rather than restoring reset authorization.

Reason:
- Account recovery must prove email possession for this operation, prevent proof reuse, and prevent an old password or refresh token from leaving a usable refresh session across reset.

Related files:
- `docs/architecture/auth-member-signup-design.md`
- `auth-service`

## 2026-10-06 - Job Payment Terms Change and Re-payment

Decision:
- Owners change payment terms (`PUT /api/jobs/{id}/payment-terms`: work date, start/end time, next-day flag, base wage, hourly extra wage, recruit count, application deadline) or re-pay (`POST /api/jobs/{id}/payment-order/retries`) only for their own `PAYMENT_PENDING` jobs. Authorization uses the JWT `memberId`; other owners' jobs are 404. Published or closed jobs, jobs with admission or seat history, jobs whose current order has applied funding state or a funding block, and jobs with an in-flight order command are rejected. Edits after publication need a separate re-recruitment policy.
- Each request requires an `Idempotency-Key`. A change request row (V13) stores the key, type, and immutable pending terms snapshot together with the next-sequence order command under the job-row lock. The same key and request return the request's current state; any different request is `JOB-409-004`. At most one order command per job is in flight; this is enforced under the job-row lock, and the latest-sequence fence remains the defense against stale results.
- The payment job version is the payment terms snapshot version, not JPA `@Version`. A terms change uses the job's highest command version + 1 (including rejected commands). A same-terms re-payment keeps the linked order's version and amount, so payment-service's rule (same version only for a FAILED order with the same amount) keeps READY re-payment from replacing a reusable order. New attempts are identified by a new issue sequence, idempotency key, and order ID.
- The current terms and order link change only in the transaction that links the verified new order. That transaction also applies the terms and never publishes or unblocks. For an already linked job, payment-service 409 is a definitive rejection: the command and request end as `REJECTED` and the job is unchanged; the rejected key is never resent. Timeouts, network errors, 5xx, and malformed or mismatched responses stay pending and converge with the same key and snapshot. Lease tokens fence late executors.
- If a new order is returned while the job can no longer be replaced (published by the earlier order or the earlier order's funding was applied), job-service does not link it, keeps the order ID on a `SUPERSEDED` command so that order's later funding is `STALE_ORDER`, and logs for operators. The link transaction reads funding state with a locking read because its read view predates the job-row lock.
- Funding that cannot publish (`PUBLICATION_SKIPPED`) and earlier-order `funded=true` create one refund-review record per order (V12), linked to the first such receipt, in the receipt transaction. No refund is executed.
- payment-service accepts a non-default Toss API base URL only for loopback HTTP test doubles.

Reason:
- payment-service already decides replacement under its own command, job, and order-row locks and rolls back rejected requests, so the job side must not finalize replaceability from a pre-check and must not overwrite valid terms before the replacement is confirmed.
- Keeping the version for same-terms re-payment matches the existing payment-service contract and keeps "same version" meaning "same terms".
- A pending-snapshot row is the smallest change that keeps current terms intact while reusing the existing durable command, lease, and retry path.

Implication for agents:
- Do not treat a READY/FAILED pre-check in job-service as replacement approval, and do not make REVIEW_REQUIRED, CONFIRMING, or DEPOSITED orders replaceable without a review or refund policy.
- Do not restore old terms or issue a new key while an outcome is unknown.
- Do not unblock or publish on order linking; only the new order's verified `funded=true` may.
- Keep the job-row → command-row → change-request-row lock order.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/toss-deposit-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobPaymentChangeCommandService.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/PaymentOrderCommandTransactionService.java`
- `job-service/src/main/resources/db/migration/V12__create_job_payment_refund_reviews.sql`
- `job-service/src/main/resources/db/migration/V13__create_job_payment_change_requests.sql`
- `payment-service/src/test/java/com/workernotfound/payment/OrderReplacementTests.java`

## 2026-10-06 - Job Owner List Query

Decision:
- Owners list their own jobs at `GET /api/jobs/me`. Only the `OWNER` role is allowed and the owner is the JWT `memberId`; no request value selects the owner. Security checks this path before the public `GET /api/jobs/**` rule.
- The list includes `PAYMENT_PENDING`, `OPEN`, `MATCHING`, `CLOSED`, and funding-blocked jobs, with an optional single `status` filter. It sorts by `created_at DESC, id DESC` and pages with the search rules (default page 0, size 20, max 100).
- Filtering, sorting, and paging run in the database, backed by the V15 `idx_job_posts_owner_created (owner_id, created_at, id)` index. Public search behavior is unchanged.
- Each card shows `confirmedCount`, the number of `CONSUMED` seat reservations, aggregated in one grouped query per page. `applicantCount` stays `null` until the applicant-count projection (#66). Payment progress is not in the card; owners use `GET /api/jobs/{id}/payment-order`.
- Request-binding conversion failures (`@ModelAttribute` type mismatches such as an unknown `status`) return 400 `GLOBAL-400-002` with the fixed reason `유효하지 않은 값입니다.` instead of Spring's raw message.

Reason:
- Owners need private and closed jobs in a management view, while public search shows only recruitable jobs.
- Owner jobs grow without bound, so in-memory filtering like search would not scale and per-job count queries would create N+1 reads.
- Raw conversion messages exposed internal class names, which the error-handling rule forbids.

Implication for agents:
- Do not accept an owner ID from the request for owner-scoped queries.
- Keep `/api/jobs/me` ahead of the public `GET /api/jobs/**` matcher, and keep the card aggregation batched per page.
- Do not add payment progress or applicant counts to this card without a new decision.

Related files:
- `docs/architecture/job-post-design.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobFindService.java`
- `job-service/src/main/java/com/workernotfound/job/global/security/SecurityConfig.java`
- `job-service/src/main/java/com/workernotfound/job/global/exception/GlobalExceptionHandler.java`
- `job-service/src/main/resources/db/migration/V15__add_owner_index_to_job_posts.sql`

## 2026-10-06 - Member Profile and Verified Contact Changes

Decision:
- Allow ACTIVE members to patch names and their own role's profile. Worker preferences/times/location and owner store name/type/location are editable; role, business registration number, and verification status are immutable in this API.
- Treat omitted/null patch fields as unchanged and supplied nonempty lists as replacements. Preserve existing child rows for unchanged unique values.
- Require a separate CONTACT_CHANGE code sent to the new email or phone and atomically consume it once. Signup and password-reset proofs cannot authorize changes.
- Persist contact-change commands in auth-service, temporarily stop login/token issuance with UPDATING, revoke every refresh session, and synchronize member-service outside auth DB transactions.
- Record stable command results in member-service so delayed replays cannot undo a later contact change. Unknown remote outcomes stay pending with durable backoff. Completed auth commands discard raw contact values.
- Existing stateless access tokens retain their expiration; users log in again after synchronization.

Reason:
- Member profiles and authentication email have separate database owners. A lost response must converge without replaying verification or rolling back a possibly committed remote change.

Related files:
- `docs/architecture/member-account-management.md`
- `auth-service`
- `member-service`


## 2026-10-06 - Block Withdrawal During Ongoing Transactions

Decision:
- The user confirmed that ongoing applications, matching, work, and payments block withdrawal. Never auto-cancel work or fabricate completed refunds/settlement.
- Use durable auth coordination and per-service prepare/release/commit gates. Creation transactions acquire the same member gate as withdrawal; unknown remote outcomes retry the original command, and finalization never switches back to compensation.
- Reject old access tokens in prepared/withdrawn services, including connected chat sockets. Restore ACTIVE only after every gate has acknowledged compensation; refresh sessions remain revoked.
- Erase auth credentials/OAuth links/tokens/contact-command personal data and member profiles/locations/preferences at successful withdrawal. Preserve member IDs/status/timestamps and domain-owned transaction evidence rather than cascading across services.
- Keep DEPOSITED/uncertain payment orders, outstanding deposits, pending Sagas and external commands as blockers until owning domains provide final completion contracts.
- Define contract/payment evidence retention as five years after closure and separate dispute evidence as three years after resolution, referencing the statutory categories documented in the architecture note. Applicability review and expired-evidence cleanup belong to domain operation work, not account deletion.

Related files:
- `docs/architecture/member-account-management.md`
- `auth-service`, `member-service`, `job-service`, `matching-service`, `work-service`, `payment-service`, `chat-service`, `notification-service`

## 2026-10-06 - Job Manual Close and Deadline-based Transitions

Decision:
- Time-based job transitions (a time equal to the boundary counts as passed; the current time is read from the `Clock` bean after the job-row lock):
  - `PAYMENT_PENDING` past the application deadline -> `CLOSED` (`SYSTEM:APPLICATION_DEADLINE_PASSED`), no matching-service notification.
  - `OPEN` past the application deadline -> `MATCHING` (`SYSTEM:APPLICATION_DEADLINE_PASSED`), never notified. A notification would make matching-service reject the existing applicants, who must still be matchable in `MATCHING`.
  - `OPEN`/`MATCHING` past work start (`workDate + startTime`) -> `CLOSED` (`SYSTEM:WORK_STARTED`), notified. An `OPEN` job past both boundaries closes directly with one history row.
- Owners close their own jobs with `POST /api/jobs/{id}/close` (`OWNER` JWT, `Idempotency-Key`). `OPEN`/`MATCHING` -> `CLOSED` (`OWNER:MANUAL_CLOSE`) is notified; `PAYMENT_PENDING` -> `CLOSED` is not. Other owners' jobs are 404, the same as the other owner APIs. The same key returns the first stored result (V18 `job_close_requests`); a key reused for another job or owner is `JOB-409-004`. The key column uses `utf8mb4_0900_bin`, so lookups and the unique constraint treat keys that differ only in case (or trailing spaces) as different keys.
- A new key on an already `CLOSED` job returns 200 `ALREADY_CLOSED` without changing the job, history, or notifications. The owner's intent is already met, and a close that loses a race to automatic close or recruitment completion must not look like a failure.
- Notified closes reuse the recruitment-completion command and its delivery: the status change, a flush to fix the `@Version`, the history row, and the command with that post-close version are stored in the caller's transaction. Recruitment completion shares the same recorder without behavior changes.
- After a close, the first confirmation of a `RESERVED` seat is rejected with `JOB-409-005`, like a funding block. Same-key retries of `CONSUMED` seats still succeed first, and seat release still works as Saga compensation. Closing does not touch application admissions.
- A per-job scheduler (`JOB_SCHEDULE_TRANSITION_*`, default `1m` interval, `30s` initial delay, batch `100`) reads candidate IDs with cursors and re-evaluates each job in its own transaction under the row lock. V17 adds `(status, application_deadline)` and `(status, work_date, start_time)` indexes.
- Existing rules still apply on closed jobs: payment-terms changes and re-payment are rejected (`JOB-409-014`), a pending change's new order is not linked (`JOB_STATE_CHANGED`), the initial order is still linked, and a later `funded=true` is `PUBLICATION_SKIPPED(JOB_CLOSED)` with refund review.
- Detail visibility is decided by whether the job was ever published (`job_posts.published`, set by `PAYMENT_PENDING -> OPEN` and never cleared), not by the current status. A job closed before publication stays visible only to its owner (others and anonymous requests get 404); a job closed after publication stays public. V19 backfills existing non-`PAYMENT_PENDING` jobs as published, except those with a `PAYMENT_PENDING -> CLOSED` history.
- Out of scope: cleanup of unpaid orders, refund of remaining deposits, reopening, job deletion, and admission status changes (#66).

Reason:
- The user confirmed these transitions and notification rules in issue #95 on 2026-10-06, and chose a 1-minute scheduler interval.
- matching-service already blocks applications at or below the notified version and compensates any 4xx confirmation rejection, so no matching-service change is needed.

Implication for agents:
- Never notify matching-service for `OPEN -> MATCHING`.
- Put new job status transitions on `JobPost` (throwing `IllegalStateException` from disallowed states) and record them through `JobStatusChangeRecorder` in the same transaction.
- Seat confirmation does not check work start directly. Between work start and the next scheduler run, a valid `RESERVED` seat can still be confirmed; changing that needs a new decision.

Related files:
- `docs/architecture/job-post-design.md`
- `docs/architecture/mvp-domain-flow.md`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobCloseCommandService.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobScheduleTransitionService.java`
- `job-service/src/main/java/com/workernotfound/job/domain/job/service/JobStatusChangeRecorder.java`
- `job-service/src/main/resources/db/migration/V17__add_schedule_transition_indexes_to_job_posts.sql`
- `job-service/src/main/resources/db/migration/V18__create_job_close_requests.sql`
- `job-service/src/main/resources/db/migration/V19__add_published_to_job_posts.sql`
