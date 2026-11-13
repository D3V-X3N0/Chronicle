# M3 implementation and qualification plan

Started 13 November 2026 from clean `main` at `5465c10`. M1/M2 and the
`19e67b1` evaluation alpha are verified baselines; build run 43107753555 passed
on Linux and Windows. M3 is in progress, not qualified or complete.

The scope is canonical stone/air, codec 1, data version 5023 on the pinned
Paper build. No inventory, block entity, multi-block or unsafe physics recovery.
Existing history and migration bytes remain immutable.

## Ordered work and gates

1. Qualify reversible application on a disposable Paper world before enabling
   recovery: independent expected blocks/neighbours, no drops/XP, loaded chunks,
   thread checks, unsafe surroundings and measured calls/slices.
2. Add schema 3 journals and bounded frozen planning. Preserve v1/v2 history;
   test migration rollback/reopen, complete later chains, effective eligibility,
   coverage gaps, preview ownership/expiry and idempotency.
3. Connect one server-thread executor to the existing single writer. Persist
   intents before application and atomic actual outcomes/audit history afterwards.
   Check revisions, full state, neighbours, history and permissions afresh; pause
   on health/uncertainty. Preserve capture order across ordinary/recovery events.
4. Restore only verified applied rollback steps, in reverse application order.
   Journal exactly-once eligibility. Quarantine interrupted steps; revalidate
   retained effects after every restart, including clean plugin shutdown.
5. Exercise independent worlds, conflicts, permissions, duplicate requests,
   partial failures, budgets, process kills at journal boundaries and mismatched
   world/database saves. Run full local and Linux/Windows CI/runtime gates, then
   update support claims and close only verified criteria.

## Safety decisions

- One bounded recovery request/preview/execution context initially. Oversized
  selections fail with narrowing guidance; no silent truncation or moving query
  at confirmation. Selection is explicit in time, world, radius and actions.
- Existing `recovery_eligible=0` is historical capture evidence, not rewritten
  by qualification. A separate versioned recovery planner validates the complete
  canonical transition, dependencies and current runtime capability.
- Only loaded targets with a fully observed inert neighbourhood may execute.
  Unknown snapshots, known gaps and later unselected changes are conflicts,
  including known changes returning to identical bytes.
- SQLite commit and world save remain separate. Restart never retries uncertain
  mutation automatically. Equality supplies review evidence, not proof of cause.
- Recovery creates no drops/XP and does not reclaim previously mined items or
  refund placement costs. It does not conserve the server economy.

Progress and exact evidence will be added as each gate is verified. M5/M6 and
release publication remain separate work.

## Current implementation evidence

The milestone is tracked in [#9](https://github.com/D3V-X3N0/Chronicle/issues/9)
(application), [#10](https://github.com/D3V-X3N0/Chronicle/issues/10) (planning and
journals), [#11](https://github.com/D3V-X3N0/Chronicle/issues/11) (execution/restore)
and [#12](https://github.com/D3V-X3N0/Chronicle/issues/12) (restart/final qualification).
Dependencies follow that order. The implemented workflow is in [RECOVERY.md](RECOVERY.md).

Local development evidence on Windows/JDK 25.0.1 includes 86 passing tests,
schema-1/2 upgrades and failed-upgrade rollback, a seeded 500-history independent
planning oracle, actual TCP-player rollback/restore, duplicate confirmation,
same-state intervening edits and permission revocation. The codec fixture performs
98 applications and compares the entire prepared region, neighbours and drops/XP.
Its initial cold maximum call was 5,579,500 ns; this is an observed overrun, not a
two-millisecond latency guarantee.

Eight actual Paper process-kill cases passed locally: before intent, after intent,
after mutation, after outcome, after explicit world save, before summary, after
clean plugin disable before world save, and during restoration. The clean-disable
case preserved a committed outcome while the reloaded world retained the earlier
state; restore correctly skipped it. Exact step/audit counts and no automatic
restart mutation were checked independently. Initial run log: `build-m3-crash.log`;
disposable directories are under `build/paper-tests/recovery-crash-*`.

Final retained-effect/permission refinements, complete M1/M2 runtime regression,
clean build, documentation checks and Linux/Windows CI are still pending. M3 is
not marked complete by this intermediate evidence.
