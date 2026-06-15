# Agent Failure Memory

This file records repeated or high-risk mistakes identified by the user and the correction rules future agents should follow.

Use this format:

```md
## YYYY-MM-DD - Short Title

Context:
- What the agent was trying to do.

Mistake:
- What went wrong.

Why it was wrong:
- Why this caused risk, confusion, or incorrect behavior.

Correct behavior:
- What future agents should do instead.

Related files:
- Optional file paths.
```

Keep entries short, concrete, and actionable.

## 2026-06-16 - Work Started Before Issue and Branch Setup

Context:
- The agent was asked to scaffold the `auth-service` package structure.

Mistake:
- The agent edited files before creating or confirming the required issue and work branch.
- The agent also proposed the wrong issue title and branch type for a chore task before the user corrected it.

Why it was wrong:
- The repository collaboration workflow requires an issue first and a matching work branch from `develop`.
- Starting work on the wrong branch makes review, traceability, and PR flow harder to manage.

Correct behavior:
- Before any code or documentation edit, check whether an issue exists and whether the current branch matches the task type and issue number.
- If no issue exists, create one using the repository's required title format before editing files.
- Create the work branch from `develop` using the task type and issue number, such as `chore/#2-auth-service-package-structure`, before applying changes.
- Match chore tasks to `[chore]` issue titles and `chore/*` branches unless the user explicitly says otherwise.

Related files:
- `docs/agent/checklists.md`
- `docs/agent/decisions.md`
