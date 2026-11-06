# AGENTS.md — self-storage

## Source of truth

- Behavior is defined by the docs inside each module: `backend/docs/` and, when the module has one,
  `frontend/docs/`.
- Code must match those docs. If docs and code disagree: **report it, do not pick a side**.
- A behavior change updates the module docs in the same PR — docs are kept as-built.
- `specs/draft.md` is **reference only**: read it for the flow, refine the behavior, then record it
  in the module docs. **Do not edit `specs/draft.md`.** `specs/db-table-draft.md` may be updated
  when the schema changes.

## Workflow — one issue behind every PR

Work starts from an issue, and the PR links back to it. There is no PR without an issue.

| Issue form | What it is | Relation to a PR |
|---|---|---|
| `be-api`, `be-server`, `fe-page` | a unit of work | the PR ships it; `Closes #N` closes it on merge |
| `bug` | behavior that runs against the module docs | the PR fixes it; `Closes #N` |
| `behavior-gap` | the module docs are silent, contradict themselves, or you want to deviate | no PR of its own — it is the decision gate for the current PR; the approved behavior lands in the module docs |
| `proposal` | you want to change something already defined | no PR of its own — approve first, then it becomes work |

```text
issue (task) → branch → PR "Closes #N" → CI green + 1 approval → merge → issue closes
                              │
                              └─ docs unclear → open behavior-gap #M → STOP
                                 → approved → docs + code in the same PR
```

- One issue may produce several PRs. One PR closes **one** issue; link any others with `#N`.
- Never `Closes` an epic. Roadmap `#30` is an index of three packages (`#33` A · `#34` B · `#35` C) —
  `Closes #33` is correct, `Closes #30` is not. Tick individual items inside the package issue.
- Every PR needs one approval from the code owner. All CI gates must be green: **PR gate**,
  **Guardrails**, **Backend CI**, **Frontend CI**. Never disable or skip tests to make a check
  pass — ask `@levihoccode` for the `waiver` label, with a stated reason, if a gate really must be
  bypassed.
- Issues created with `gh issue create` skip the form — fill the form's fields by hand.
- Before opening a PR, read [`docs/guides/pull-request.md`](docs/guides/pull-request.md) and fill
  [`.github/pull_request_template.md`](.github/pull_request_template.md). The PR body is the
  end-of-task review guide below, not a changelog; its required sections are not optional.

## Evidence is required

"Probably works" is not acceptable. Every claim needs **one of**:

- real command output (build / test / lint / curl), or
- a specific `path/to/file:line`.

Banned: "should work", "probably", "likely", "I think". If you cannot verify something, say so
plainly: **"not verified, because &lt;reason&gt;"**.

## Before writing code

1. **Read before you write.** Open the actual file/class/component to read its signature — never
   guess an API.
2. **Plan before you edit.** More than 2 files, or touching schema / migration / API contract /
   payment → present the plan and the risks, then wait for approval.
3. **Do not expand scope.** No refactoring beyond the request. Ask before adding a dependency.
4. **Never edit a test to make it pass.** Fix the cause; changing a test needs a stated reason and
   approval.

## Docs — writing rules

Docs (`backend/docs/`, `frontend/docs/`, `README.md`) describe behavior, not the work that
produced it.

- Docs are the behavior authority; `specs/draft.md` is frozen reference — never edit it.
  `specs/db-table-draft.md` may follow schema changes.
- If a rule only exists in `specs/draft.md`, write the implemented rule into the module docs before
  relying on it.
- Do not use work-split codes (`A3a`, `A4`, `B1`, `B2`, …). They are temporary labels for
  coordinating tasks and mean nothing to a later reader — name the flow or the rule instead.
- Do not cite issue or PR numbers. State the problem and how the code handles it, briefly:
  "Seed thiếu policy `order.expiry_days` — migration V5 bổ sung", not "bug #130".
- Docs record what is already implemented (as-built). Open work lives in issues, not in docs.

## Definition of done

- Backend: `cd backend && ./mvnw -B verify` green.
- Frontend: `cd frontend && npm run build && npm run format:check` green.
- Runtime changes (DB, API, payment, cron) must be actually run, not just compiled.

## Required end-of-task report — all four sections

1. Files changed.
2. Commands run — with results.
3. **What was not verified.**
4. Remaining risks + what the reviewer should look at.

Split **Đã đổi gì** into subsections by area of change — trigger events/entry points, API, logic,
concurrency/dedup, data & module, audit, tests, docs/infra. Drop sections that don't apply; each
item says what changed, not a file dump.

## Self-challenge

Before saying "done", name **one weakness** in your own solution: an untested path, an unchecked
assumption, a fragile spot. No counter-argument means it is not a conclusion yet.

## Git

- Conventional commits with a scope: `feat(booking): …`, `fix(payment): …`, `docs(flow-2): …`.
- Commit messages are in **English** — subject and body. PRs are squash-merged, so the PR title
  becomes the commit subject: keep PR titles English too; the PR body stays Vietnamese.
- Branch names follow the pattern already in use: `specs/<topic>`, `be/feat/<topic>`, `fe/feat/<topic>`, `docs/<topic>`, `chore/<topic>`.
- One commit = one logical change. Do not commit unless asked.
- **Never force-push — `main` especially.** No `--force`, no `--force-with-lease`, no history
  rewriting on any shared branch. A wrong commit on a shared branch is fixed forward (revert),
  never rewritten.
- **Never push directly to `main`.** Every change goes through branch → PR, even docs; only an
  explicit "push to main" from the human overrides this — anything ambiguous means branch → PR.
- **Merging is a human-only action.** Agents never approve or merge PRs (no `--auto`, no `--admin`)
  and never resolve review threads to unblock a merge — review, push when asked, report; the human
  approves and merges.
- Never commit `.env`, credentials, or tokens — sandbox keys only.

## Per layer

- Backend: `backend/AGENTS.md`
- Frontend: `frontend/AGENTS.md`
