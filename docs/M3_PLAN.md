# M3 implementation and qualification evidence

Started 13 November 2026 from clean `main` at `5465c10`. M1/M2 and the
`19e67b1` evaluation alpha are verified baselines; build run 43107753555 passed
on Linux and Windows. M3 completed its qualified inert-block scope on 13 November
2026; M5/M6 and any new release remain separate gates.

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

The evidence below records the verified gates. M5/M6 and release publication
remain separate work.

## Implementation evidence

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

## Final local acceptance

Implementation commit `5dbee17f938d80416bc572975346b243654c255d` passed on
Windows 11 Home build 26200 / Oracle JDK 25.0.1 with the locked toolchain
(AMD Ryzen 9 7950X, 16 cores/32 logical processors; Paper subprocesses use
256 MiB initial/1 GiB maximum heap):

```powershell
.\gradlew.bat clean build paperIntegrationTest paperPacketTest paperRecoveryTest -PacceptMinecraftEula=true --no-daemon --console=plain
```

The run completed successfully in seven minutes: **86 tests, zero failures,
errors or skips**, all three Paper harnesses, all eight crash/restart cases and
four stopped-server backup phases. Permission revocation after durable intent,
explicit pause/fresh continuation and retained partial restoration passed. The
independent world and database backup restores each produced zero applied steps
and one explicit skip, preserving the independently expected world.

The M1/M2 packet fixture still returned exact IDs `[6,5,4,3,2,1]` before and after
restart. The final codec run performed 98 applications, with a measured maximum
call of 5,116,500 ns and slice of 5,140,500 ns. These are scheduling observations,
not performance or capacity qualification. The production executor's command
fixture also reported a slice overrun explicitly.

Local evidence: `build-m3-acceptance.log`, and under `build/paper-tests/`,
`recovery-6781132956909454316`, `recovery-packets-14717385626956587848`,
`recovery-backups-2850905147660450877` and the eight `recovery-crash-*` runs named
in that log. Generated worlds/databases are disposable and excluded from Git.
The plugin `chronicle-0.2.0-SNAPSHOT.jar` SHA-256 is
`7102a3d7e2e9104836d43986b696055e36a74831fd62e6ca88a71c75363c76dd`.
The checked-in MIT license, dependency locks/checksums, server pin and migrations
1/2 are unchanged. UTF-8/newlines, whitespace and repository Markdown links/anchors
passed documentation checks.

A test-only follow-up strengthens the after-intent crash scenario: an unrelated
change to desired bytes still reports `UNKNOWN live=DESIRED`, refuses restoration
and preserves zero operation audit rows. Expected and unrelated/conflicting state
branches also pass. Its initial assertion expected the later no-applied-steps guard;
it was corrected to the earlier unfinished-operation refusal actually returned.
`build` and the focused actual-server scenario passed (`build-m3-review-fixed.log`,
`recovery-crash-after-intent-3843686043458717142`). Production JAR bytes are unchanged.

## Cross-platform completion

Final [runtime run 43501845007](https://github.com/D3V-X3N0/Chronicle/actions/runs/43501845007)
passed on Linux and Windows at `a06b4768fdc52e64d12278915a243b0f21a4f357`.
Both downloaded JUnit reports contain **86 tests and zero failures/errors/skips**.
Both jobs passed all three Paper harnesses, all eight crash/restart boundaries,
the strengthened unknown/desired-state review and all four backup phases.
The [push build](https://github.com/D3V-X3N0/Chronicle/actions/runs/43501843243)
also passed both operating systems. The preceding implementation's
[runtime run](https://github.com/D3V-X3N0/Chronicle/actions/runs/43500990970)
passed both as well; no runtime failure was waived.

| Environment | Codec applications | Maximum call (ns) | Maximum slice (ns) |
| --- | ---: | ---: | ---: |
| Local Windows | 98 | 5,116,500 | 5,140,500 |
| Linux CI | 98 | 3,337,607 | 3,893,498 |
| Windows CI | 98 | 9,291,000 | 9,323,500 |

These single-run scheduling observations show why the two-millisecond budget
cannot be a hard API-call latency guarantee. They do not replace M5 profiling.
The Windows CI plugin matches the local SHA-256 above. The Linux plugin SHA-256 is
`0f18da29e1f0a0534623125923ca2fba1e614f2a970dcf63a3013f745668e3e2`.
Comparing every ZIP entry found only `META-INF/LICENSE` differed, with identical
text after CRLF/LF normalization; no broader reproducibility claim is made.
CI retains JARs, JUnit reports and server/restart logs for seven days; workflow logs
and this committed evidence identify the exact revision and reproduction commands.

M3's four focused issues and milestone are complete for canonical inert stone/air
only. Inventory/economy restoration, broader physics/capture, production performance,
retention and backup commands remain excluded or future work. No new release has
been published; `v0.1.0-alpha.1` remains the M1/M2 evaluation release.
