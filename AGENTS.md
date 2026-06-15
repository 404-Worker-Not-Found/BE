# AGENTS.md

## Purpose

This file defines how AI agents should work in this repository.

Keep this file focused on AI behavior rules.

## Document Map

- `PROJECT_CONTEXT.md`: current project state and context
- `.agent/decisions.md`: decisions the project has already made
- `.agent/failure-memory.md`: repeated or high-risk mistakes identified by the user
- `.agent/checklists.md`: verification checklists
- `scripts/verify.sh`: repository-wide verification entry point

## Required Reading

Before making code changes, agents must read:

- `PROJECT_CONTEXT.md`, if it exists
- `.agent/decisions.md`
- `.agent/failure-memory.md`
- `.agent/checklists.md`

If the task affects a specific service, inspect that service's source code, build file, configuration, and tests before editing.

For `auth-service`, inspect as needed:

- `auth-service/build.gradle`
- `auth-service/src/main/resources/application.yaml`
- `auth-service/src/main/java`
- `auth-service/src/test/java`

## Work Principles

- Keep changes scoped to the user's request.
- Do not modify unrelated files.
- Prefer existing project conventions over new patterns.
- Do not introduce new architecture without a clear reason.
- Do not hardcode secrets, credentials, tokens, or local-only values.
- Explain assumptions when project context is incomplete.
- Prefer small, reversible changes when requirements are ambiguous.
- Preserve user changes already present in the working tree.

## Decision Priority

When `PROJECT_CONTEXT.md` and `.agent/decisions.md` conflict:

- Follow `.agent/decisions.md`.
- Report the conflict.

## Verification

Use `./scripts/verify.sh` as the repository-wide verification command.

During iterative work on a single service, a smaller service-level command may be used first. Before completing work, run repository-wide verification unless the user explicitly asks not to or the current task is documentation-only.

If verification cannot be run or fails, report:

- the command that was run
- the failing service or step
- the likely cause, if known
- whether the failure is related to the current task

## Agent Document Updates

Follow the `Documentation Update Check` in `.agent/checklists.md`.

When the user identifies a repeated mistake, propose an entry for `.agent/failure-memory.md`.

Only add or update `.agent/failure-memory.md` after the user asks for the entry to be recorded or clearly approves the proposed entry.

At the end of a task, briefly report whether any agent document update is needed.
If none is needed, say: "No agent document update needed."

## Completion Routine

Before reporting completion:

- Confirm relevant files were inspected.
- Confirm changes are scoped to the request.
- Run required verification, or report why it was not run.
- Confirm no secrets were added.
- Check whether `PROJECT_CONTEXT.md`, `.agent/decisions.md`, `.agent/failure-memory.md`, or `.agent/checklists.md` needs an update.
