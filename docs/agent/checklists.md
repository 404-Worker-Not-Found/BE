# Checklists

This file records verification checklists. Use it to check whether decisions and recurring process requirements are being followed.

Do not add one-off task notes. Add or update a checklist only when the check is likely to recur.

## Documentation Update Check

At the end of a task, check:

- Was a new decision made?
- Did a previous decision change?
- Did the user identify a repeated AI mistake?
- Did the current project state significantly change?
- Is a new recurring verification checklist needed?

If no update is needed, report:

```text
No agent document update needed.
```

## Repository Verification

For repository-wide verification, run:

```bash
./docs/scripts/verify.sh
```

If verification fails, check:

- What command was run?
- Which service or step failed?
- What is the likely cause, if known?
- Is the failure related to the current task?

## Architecture Check

When a task changes architecture or service structure, check:

- Does the change keep the backend monorepo structure?
- If separate repository separation is proposed, is the operational reason explained?
- Does the change assume only implemented services exist?
- If a planned service is referenced, is it clear that the boundary is provisional?
- Are data ownership and transaction boundaries explained?
- Are cross-service references modeled as external IDs instead of physical database foreign keys?
- Are API/event contracts or consistency boundaries explained for cross-service workflows?

## Technology Baseline Check

When a task changes build configuration, dependencies, or service scaffolding, check:

- Does the change align with Java 17?
- Does the change align with Spring Boot 4.1.0?
- Does it remain compatible with MySQL and Redis?
- If current scaffolding differs from the baseline, is the mismatch reported?
- If an incompatible stack change is proposed, is the justification explicit?

## Test Datasource Check

When a task changes persistence, JPA, Flyway migrations, or integration tests, check:

- Does `auth-service` still use Testcontainers with MySQL for integration-test datasource setup?
- Does repository verification still run through `./docs/scripts/verify.sh`?
- Are new database-dependent tests using shared test support instead of ad hoc local database configuration?
- If Redis behavior becomes part of the test path, is Redis Testcontainers support added or explicitly deferred?

## Authentication Strategy Check

When a task changes authentication behavior, check:

- Does the design assume Access Token + Refresh Token authentication?
- If session-based authentication is proposed, did the user explicitly request it?
- Are refresh token storage, rotation, expiration, and revocation addressed?
- Is Spring Security kept enabled for production behavior?
- Are passwords, tokens, authorization headers, and secrets kept out of logs?

## Agent Memory Check

When agent documents are updated, check:

- Does `AGENTS.md` contain AI behavior rules only?
- Does `docs/PROJECT_CONTEXT.md` describe current project state only?
- Does `docs/agent/decisions.md` record decisions?
- Does `docs/agent/failure-memory.md` record only user-identified repeated mistakes?
- Does `docs/agent/checklists.md` contain verification checklists?
- Does `docs/agent/coding-rules.md` contain AI-facing backend coding rules?

## Collaboration Workflow Check

When starting or preparing a new unit of work, check:

- Was an issue created first?
- Does the issue title follow `[type] title`, such as `[chore] 스타일 시스템 추가`?
- Are the issue title and body written in Korean?
- Does the issue use the repository issue template?
- Was the work branch created from `develop`?
- Does the branch name follow `{type}/#{issue-number}-{short-description}`, such as `chore/#2-auth-service-package-structure`?
- Does the branch type match the work type, such as `feature/*`, `fix/*`, `docs/*`, `refactor/*`, `test/*`, `build/*`, `ci/*`, `chore/*`, or `environment/*`?

When committing, check:

- Does each commit follow `type: 작업 내용`, such as `feat: 노트 CRUD`?
- Is the commit type one of `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, or `environment`?

When opening or updating a PR, check:

- Does the PR target `develop`?
- Does the PR title follow `[type] PR 제목`, such as `[feat] 노트 CRUD`?
- Are the PR title and body written in Korean?
- Is the PR author assigned as the assignee in PR metadata?
- Does the PR use the repository PR template?
- Is the related issue linked?
- Does the related issue section avoid auto-closing keywords such as `Close`, `Closes`, `Closed`, `Fix`, `Fixes`, `Fixed`, `Resolve`, `Resolves`, or `Resolved`?
- Is repository verification handled in the agent's final report instead of being written into the issue or PR body?
- Does the issue or PR body avoid command-specific AI verification notes such as `./gradlew test passed`, `./docs/scripts/verify.sh passed`, or similar agent execution notes?
- Does the PR template keep generic human-facing checklist items such as `테스트를 통과했나요?` when useful?

When merging a PR, check:

- Did CodeRabbit complete an initial review of the PR's changes?
- Do the configured review path filters include all changed paths?
- Were post-review changes assessed for security, payment, concurrency, data integrity, transaction, or external-integration risk?
- If a post-review change introduces nontrivial behavior in those areas or its impact is unclear, were related fixes and verification batched, then a manual `@coderabbitai review` requested? Automatic incremental reviews are disabled.
- When re-review is required, did CodeRabbit actually review the commit containing the high-risk changes? A green status with a rate-limit, skipped-review, or in-progress notice is insufficient; wait for the stated reset time before retrying.
- If re-review is not required, were the remaining changes checked directly, relevant verification run, and the reason for skipping re-review reported?
- If automatic reviews paused after `auto_pause_after_reviewed_commits`, was a manual review requested when the initial review or a risk-based re-review was required?
- Was the mandatory CodeRabbit review preserved rather than replaced with the agent's own review?
- Were its findings checked against code and project contracts, and were valid findings fixed and verified?
- Were any unaccepted findings recorded with their reasons?
- Is squash-and-merge being used?
- Will the branch be deleted after merge?
- Will the next unit of work start from a fresh branch?
