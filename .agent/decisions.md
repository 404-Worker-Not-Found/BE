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
- `scripts/verify.sh`

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
- `PROJECT_CONTEXT.md`
- `urgent_job_service_msa_erd_v1.drawio`
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
- `urgent_job_service_msa_erd_v1.drawio`
- `PROJECT_CONTEXT.md`

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
- `PROJECT_CONTEXT.md`

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
- `PROJECT_CONTEXT.md` describes the current project state.
- `.agent/decisions.md` records decisions.
- `.agent/failure-memory.md` records repeated mistakes identified by the user.
- `.agent/checklists.md` records verification checklists.

Reason:
- Each document has a different purpose and update cadence.

Implication for agents:
- Put new information in the document matching its purpose.
- Do not duplicate the same rule across multiple documents unless a short reference is needed.

Related files:
- `AGENTS.md`
- `PROJECT_CONTEXT.md`
- `.agent/decisions.md`
- `.agent/failure-memory.md`
- `.agent/checklists.md`

## 2026-06-15 - Decision Priority

Decision:
- When `PROJECT_CONTEXT.md` and `.agent/decisions.md` conflict, follow `.agent/decisions.md` and report the conflict.

Reason:
- `PROJECT_CONTEXT.md` describes current state and may become stale. `.agent/decisions.md` records what has been decided.

Implication for agents:
- Treat `.agent/decisions.md` as the higher-priority source for decisions.
- Do not silently resolve conflicts.

Related files:
- `PROJECT_CONTEXT.md`
- `.agent/decisions.md`

## 2026-06-15 - Repository Verification Entry Point

Decision:
- Use `./scripts/verify.sh` as the repository-wide verification command.

Reason:
- A single verification entry point lets humans, agents, and future CI run the same check.

Implication for agents:
- Run `./scripts/verify.sh` before completing non-documentation tasks unless the user explicitly asks not to.
- Add new service checks or smoke tests to `scripts/verify.sh` as the project grows.
- If verification fails, report the failing step and whether it appears related to the current task.

Related files:
- `scripts/verify.sh`
- `.agent/checklists.md`
- `AGENTS.md`

## 2026-06-15 - Test Datasource Strategy

Decision:
- Use Testcontainers with MySQL for `auth-service` integration-test datasource setup.

Reason:
- The service uses MySQL, JPA, and Flyway, so tests should run against a real MySQL-compatible database instead of an H2 approximation or a developer-managed local database.
- Testcontainers keeps local and future CI verification reproducible.

Implication for agents:
- Do not replace the test datasource with H2 or a manually managed local MySQL database unless a later decision changes the strategy.
- Put shared Spring Boot integration-test container setup in test support code.
- Add Redis Testcontainers support later when tests start depending on Redis behavior.

Related files:
- `auth-service/build.gradle`
- `auth-service/src/test/java/com/workernotfound/auth/support/IntegrationTestSupport.java`
- `scripts/verify.sh`

## 2026-06-15 - Failure Memory Requires User Identification

Decision:
- Do not let agents independently decide that a mistake should be recorded in `.agent/failure-memory.md`.
- When the user identifies a repeated mistake, propose an entry and record it only after user approval.

Reason:
- Agents often cannot reliably judge whether their own behavior is a recurring mistake. User confirmation prevents incorrect or noisy rules from accumulating.

Implication for agents:
- Propose failure-memory entries only when the user identifies a repeated mistake.
- Do not update `.agent/failure-memory.md` without explicit user request or clear approval.

Related files:
- `.agent/failure-memory.md`
- `AGENTS.md`

## 2026-06-15 - Branch Strategy

Decision:
- Use Git Flow for collaboration.
- Use `develop` as the integration branch for development work.
- Create `feature/*` branches from `develop` for new feature work.
- Create `fix/*` branches for bug fixes.
- Include the issue number in the branch name, such as `feature/#20-note-release`.

Reason:
- A consistent branch flow keeps work isolated and makes integration testing through `develop` predictable.

Implication for agents:
- Do not work directly on `develop` unless explicitly requested.
- For a new unit of work, follow this flow: create issue, create issue-number branch from `develop`, work locally, commit and push, then open a PR to `develop`.
- Use `feature/*` for feature work and `fix/*` for bug fixes unless the user specifies a different branch type.

Related files:
- `.agent/checklists.md`

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
- `.agent/checklists.md`

## 2026-06-15 - Issue Convention

Decision:
- Use issue titles in the format `Type: title`, such as `Chore: 스타일 시스템 추가`.
- Use the repository issue template for issue content.

Reason:
- Consistent issue titles and content make planned work easier to scan and track.

Implication for agents:
- When creating or proposing issues, include issue type, feature description, task checklist, and reference links when available.
- Follow the issue template instead of inventing a new format.

Related files:
- `.github/ISSUE_TEMPLATE/task.md`
- `.agent/checklists.md`

## 2026-06-15 - Pull Request Convention

Decision:
- Use PR titles in the format `[type] PR 제목`, such as `[feat] 노트 CRUD`.
- Target `develop` for development PRs.
- Assign the PR author as the assignee in PR metadata.
- Use the repository PR template for PR content.
- Do not put command-specific AI verification notes, such as `./gradlew test passed`, in issue or PR bodies.
- Generic checklist text such as `테스트를 통과했나요?` is allowed in PR templates.

Reason:
- Consistent PR metadata makes review ownership and change intent clear.

Implication for agents:
- When creating or proposing PRs, use the `[type] title` format.
- Set the PR assignee to the PR author through PR metadata when tool access allows it.
- Include related issue, work purpose, work contents, optional screenshots, and checklist.
- Report command-specific verification results in the agent's final response, not as AI-flavored prose in issue or PR templates.

Related files:
- `.github/pull_request_template.md`
- `.agent/checklists.md`

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
- After merge, expect the feature/fix branch to be deleted before starting new work.

Related files:
- `.agent/checklists.md`
