# Testing and validation

Status: M1 and M2 unit, database, crash, packaged-native, investigation, packet/restart and initial synthetic load checks are implemented, 11 November 2026. Broader recovery/operational performance suites below remain planned. Actual commands and runtime setup are in [DEVELOPMENT.md](DEVELOPMENT.md).

## Alpha release evidence

The [first alpha notes](releases/v0.1.0-alpha.1.md) define the advertised scope. Each published alpha includes `VALIDATION.md` naming the exact source commit, distributable SHA-256, local commands/results and Linux/Windows CI links, alongside `SHA256SUMS`. This keeps release evidence attached to the tested artifact. M2 measurements below remain tied to their original revisions; packaging the alpha does not extend those results into production-capacity or recovery claims.

## Current M2 evidence

M2 retains M1's capture boundary and adds [investigation](INVESTIGATION.md). The
implementation plan and tracked acceptance gates are in [M2_PLAN.md](M2_PLAN.md).

- **Passed locally:** query filters against an independent seeded 1,004-row oracle,
  combined actor/world/action/time scopes, exact positions and negative circle edges,
  all heights, equal/backwards timestamps and inserts between fixed-boundary pages.
- **Passed locally:** v1-to-v2 upgrade retains IDs/snapshots/canonical payloads and
  backfills historical/current aliases. A late migration failure leaves no added
  columns/tables/ledger entries; reopening after correcting the fixture succeeds.
- **Passed locally:** ambiguous/unknown player/world identities, parameter-bound
  hostile input, unsupported snapshot/attribution labels, cancellation during SQL,
  expired requests, reader ownership and reuse after interruption.
- **Passed locally:** reader admission counts queued, running and undelivered work
  against 34 retained tasks; excess admission is rejected. Cancelled work is not
  delivered. Cursors enforce ownership, fixed boundaries, expiry and 128 contexts.
- **Passed locally:** paginated console investigation returned IDs `[6,5,4,3,2,1]`
  before and after a real Paper restart, with explicit boundaries and coverage.
  The independent SQL oracle verified the same six complete transitions and clean
  sessions. No-op, denied, unsupported and cancelled actions added no successful rows.
- **Passed locally:** a second separately loaded protection plugin cancelled place
  and break at LOWEST, LOW, NORMAL, HIGH and HIGHEST. Original cancellation/build
  denial and same-tick repeated-action fixtures remain passing.
- **Passed locally:** actual packet-driven inspection prevented stone breaks, held
  stone placement and chest opening; prior block/item denial returned no history,
  and offhand generated no duplicate lookup. The real player's revoked permission
  suppressed an already-admitted query's asynchronous result.
- **Passed locally:** presentation reload applies validated runtime details/page size;
  changed storage settings and malformed configuration preserve active settings.
- **Passed on GitHub Actions:** [run 43084164874](https://github.com/D3V-X3N0/Chronicle/actions/runs/43084164874)
  built revision `1c8d7f9` on Linux and Windows with JDK 25; all 65 tests passed with
  no skips. This run did not start Minecraft. Final expanded checks are recorded below.

Final local acceptance: JDK 25.0.1 on Windows 11, `clean build paperIntegrationTest
paperPacketTest -PacceptMinecraftEula=true` passed with **68 tests** (43 unit/packaging and 25 database/native/crash), zero failures,
errors or skips. This also checks inspection-mode clearing across logout/rejoin,
reader/capture off-thread refusal, reload permission revocation and the 64 KiB
configuration bound. The runtime artifacts were `smoke-1627604915803460554`,
`invalid-config-3329628191706862685` and `packets-8237387263520448904`. Plugin SHA-256:
`725975cf52eaecc6caa59b3fe2600bcbd7ed25972403f951ebf9ef1524ac2fb7`.
A final regression also bounds oversized stored UUID/action fields, labels malformed identities unknown and verifies a space/Unicode/fragment-containing database path. The full local runtime suite and a four-variant 10k mixed-load rerun passed after this hardening. Production revision `4525bc3` passed all 68 tests on Linux and Windows in [run 43086413448](https://github.com/D3V-X3N0/Chronicle/actions/runs/43086413448).

Additional cross-platform runtime evidence: [opt-in run 43085748437](https://github.com/D3V-X3N0/Chronicle/actions/runs/43085748437)
passed on Linux and Windows at `530258c`, including `clean build` (67 tests) and both
real Paper harnesses with explicit EULA acceptance. Its ordinary
[push run 43085747438](https://github.com/D3V-X3N0/Chronicle/actions/runs/43085747438)
also passed. These runs retain reports and sanitized runtime logs through the normal
seven-day artifact policy.

The final logout regression restores `chronicle.inspect` permission after reconnect,
without toggling mode, before asserting normal chest opening. This prevents lost
permission from masking a stale inspection mode. `clean build paperPacketTest
-PacceptMinecraftEula=true` passed all 68 tests and the strengthened packet fixture
in `packets-18225316516335517653`; the production JAR hash remains unchanged.
Local Markdown links/anchors, UTF-8/final-newline checks and `git diff --check` passed.

The first revocation fixture used Paper's scheduled command path, so it revoked
permission before query admission. It now invokes the registered executor
synchronously against the actual player and revokes immediately after admission,
with network-message and drained-queue assertions. This corrects the test ordering;
no permission safeguard was relaxed.

[M2 measurements](M2_MEASUREMENTS.md) report the synthetic index/mixed-load workload
and startup cost. These are not real-server throughput/MSPT or production capacity
claims. Only qualified stone/air capture and the named controlled protection and
inspection interactions are supported by this evidence. No recovery, expanded event
family, hardware power-loss test or comprehensive third-party compatibility exists.

## Current M1 evidence

- **Passed locally:** JDK 25.0.1 on Windows 11; `clean build paperIntegrationTest paperPacketTest -PacceptMinecraftEula=true`, using the pinned toolchain/server. All **54 tests passed**: 34 unit/packaging tests and 20 file-backed database/native/writer/process-crash tests, with zero skipped tests.
- **Passed locally:** packaged plugin startup/clean shutdown, console permissions, off-thread capture refusal before world access and synthetic dispatch; a fresh server rejected invalid config version 999 without modifying the file.
- **Passed locally:** all ten actual TCP player scenarios in [CAPTURE_QUALIFICATION.md](CAPTURE_QUALIFICATION.md), followed by independent SQL assertions for exactly six ordered changes. They retained exact world/player UUIDs, coordinates, capture keys, before/after states, codec/data versions and completion evidence after a second server start. Cancelled, denied, replacement and no-op attempts added no successful rows. Replacement persisted an unsupported incident. Both sessions drained cleanly.
- **Passed locally:** real SQLite busy-lock exhaustion; injected I/O failure; in-flight record/byte bounds and whole-group rejection; accurate oldest-pending age as earlier batches commit; bounded forced-shutdown timeout; migration checksum/newer/partial/corrupt/unavailable database refusal; idempotent replay and payload mismatch; actual second-member abort with no partial group.
- **Passed locally:** unchecked/native exceptions after the first group member roll back the entire transaction before restoring auto-commit. Both regression cases reproduced partial rows before fix `61e41e2` and now leave zero changes/groups/identities; exact replay then succeeds.
- **Passed locally:** externally killed JVMs at buffered, first-row, pre-commit, post-commit and acknowledged boundaries. The two-member transaction was absent before commit and complete after commit; every killed session remained unclean. These are process-death tests, not simulated power failures.
- **Passed locally:** isolated distributable native loading of SQLite 3.54.1 and retained Windows/Linux native resources/license notices. Real Paper testing exposed its parent-classloader SQLite 3.49.1 collision; the isolated-driver implementation now loads Chronicle's pinned engine and passes the runtime checks.
- **Passed locally:** both Paper tasks reject absent EULA opt-in before creating any server directory.
- **Passed on GitHub Actions:** [run 43073936671](https://github.com/D3V-X3N0/Chronicle/actions/runs/43073936671) built code revision `61e41e2` on Linux and Windows with JDK 25. Both jobs passed `clean build`, including the 54 tests and isolated packaged-native loading, and retained reports/artifacts. This push run did not start Paper; runtime evidence above is local.

An earlier Linux CI attempt could not resolve the JDBC POM from Maven Central. The identical revision passed on retry in [run 43072942407](https://github.com/D3V-X3N0/Chronicle/actions/runs/43072942407); no dependency pin or verification checksum was relaxed. The final code run above passed on its first attempt.

The final local validation artifact SHA-256 was `9098e8b0e775f58c8ee0cdd291e66e7ebe673277f577843f33fdce6e8bac8235`. Generated evidence was in `smoke-4394598689122626298`, `invalid-config-3460890698072332589` and `packets-8448338411254463529` beneath `build/paper-tests/`. These are disposable test identities/worlds, not production data. The checksum identifies the validation build at `61e41e2`; later source changes require fresh evidence.

Limits at the M1 revision: only the documented stone/air transitions and controlled protection fixture are qualified; no comprehensive third-party protection matrix, hardware power-loss/disk-full test, performance claim, inventory codec, lookup or recovery capability exists. Shutdown/native-stall acknowledgement limitations are explicit in [STORAGE.md](STORAGE.md).

## Historical bootstrap evidence

- **Passed locally:** JDK 25.0.1 on Windows 11, Gradle 9.9.1, `clean build` followed by `build paperIntegrationTest -PacceptMinecraftEula=true`, Paper API `26.3.build.248-stable`, and JUnit 6.1.3. All 23 tests passed: 12 configuration cases, five command/permission cases, four observation-buffer cases, one packaged-artifact check and one Windows/partial-log decoding regression.
- **Passed locally:** invoking `paperIntegrationTest` without acceptance fails before server download/startup and creates no server directory.
- **Passed locally after explicit EULA acceptance:** Paper 26.3 build 248 loaded the packaged plugin, executed the real console status command, passed synthetic event/permission assertions, and shut down cleanly. A second fresh server rejected configuration version 999, kept the file intact, and shut down successfully.
- **Passed locally:** an offline forced rebuild of the main JAR produced the same SHA-256, `18f8ddb2fc4baece5e1375142da8c03c07a404ea4611c48de3c881523c4436ef`, as the artifact used in the passing runtime checks. This establishes repeatability in the measured environment, not cross-machine byte identity.
- **Passed on GitHub Actions:** the Linux and Windows jobs in [bootstrap run 43067402499](https://github.com/D3V-X3N0/Chronicle/actions/runs/43067402499) built commit `1a543d4` with JDK 25, verified the Wrapper, ran `clean build`, and uploaded artifacts/test reports. This push run did not start Paper; runtime evidence above is local. Check the [Build workflow](https://github.com/D3V-X3N0/Chronicle/actions/workflows/build.yml) for subsequent revisions.
- At the bootstrap revision, real-player capture, persistence and process-crash behaviour were unverified. Current M1 evidence above supersedes those gaps only for its stated subset; recovery/performance remain future work.

The separately packaged `ChronicleFixture` observes attempts at `MONITOR`, including cancellation and build denial, and labels later samples as observations rather than event post-states. The passing synthetic runtime cases produced:

| Controlled case | Captured before / during callback | Later sample | Evidence limit |
| --- | --- | --- | --- |
| Place stone | air / stone | stone | Synthetic action, not a player packet |
| Cancelled or build-denied placement | air / stone, with cancellation/denial flag | air | Attempt must not become a successful-change record |
| Break stone | stone / stone | air | Callback state precedes the controlled mutation |
| Cancelled break | stone / stone, cancelled | stone | No successful mutation |
| Same-tick break then place | two ordered attempts: stone / stone, then air / stone | stone for both | The later sample misses the intermediate air state |

The fixture proves these controlled dispatch/sample outcomes on the pinned runtime. It does not prove vanilla event timing or qualify production capture. The separate packet-driven evidence above supplies the narrow M1 qualification; this synthetic fixture alone cannot supply it.

Unit reports are generated at `build/reports/tests/test/index.html`; runtime logs, when run, are kept under `build/paper-tests/`. Preserve needed evidence before invoking `clean`. The remaining sections define target suites, not claims that those systems exist.

Correctness gates precede throughput tuning. Every supported event/state family needs an independent expected result, and every recovery step needs a defined outcome when interrupted. A passing mock test cannot establish Paper event timing or persistent world-save behaviour.

## Test layers and execution

| Layer | Infrastructure | Scope | Proposed schedule |
| --- | --- | --- | --- |
| Pure Java unit | JUnit Jupiter, fake clock, deterministic executor/world/storage interfaces | Models, filters, ordering, admission arithmetic, conflict planning, journal state transitions | Every PR through `test` |
| Database integration | Actual pinned SQLite JDBC, temporary on-disk database and WAL, independent connections/processes | Transactions, migrations, retries, indexes, readers/writer, lifecycle, backup/pruning | Every PR through `integrationTest` |
| Event adapter tests | Small fakes or a compatible Paper mocking library if useful | Cancellation branches, extraction and handler routing | Every relevant PR; not a runtime-fidelity claim |
| Paper integration | Exact pinned server JAR/checksum, Java 25, disposable world, fixture plugin and/or test clients | Real event order, inventories, physics, main-thread rules, commands, permissions, chunk/save behaviour | Relevant PRs, required release checks; `paperIntegrationTest` |
| Failure and recovery | Subprocess controller, fault-injection storage adapter, disposable disk/VM where needed | Crash boundaries, timeouts, disk failures, unclean sessions, independent world/database restores | Relevant changes plus scheduled/release jobs |
| Load and benchmarks | Deterministic generator, seeded histories, JVM profiler and real Paper scenarios | Throughput, tail latency, allocations, saturation, query plans and tick impact | Baseline in M2; scheduled and M5/release runs |

Unit and database suites run without Minecraft and without internet after dependencies are resolved. The Paper suite is separate because it downloads/runs a specific server and needs controlled fixtures and EULA-compliant setup. Explicitly report skipped runtime checks; never count them as passed.

The harness should expose readiness/result files or a local test channel rather than guessing success from fixed sleeps. Use bounded deadlines, capture stdout/stderr, and stop the entire fixture process tree on failure. Independent fixture code defines expected world/history results. For actions that require real player packets, use controlled test clients or documented manual fixtures until automation exists; firing a synthetic event alone is insufficient.

## Core invariants

- Every eligible observed action is accounted for by a complete committed transition, an explicitly pending record, or a visible rejected/uncertain outcome. “Accepted” never implies durable.
- Cancelled, denied and proven no-op actions are not recorded as completed mutations.
- Distinct same-location changes retain order; retrying a capture key cannot add another logical change.
- A batch/group commits completely or not at all. A successful commit acknowledgement survives process termination/reopen under the tested storage model.
- A rollback never changes an unselected/conflicting state silently; original evidence survives all recovery operations.
- Restore only inverts verified applied steps from its referenced rollback, and no effective step is restored twice.
- Interrupted operations never automatically mutate ambiguous world state at restart.
- World API access stays on its owning thread; queue/plan/query memory stays within configured limits.

For pure planning, generate small random histories and compare results to a simple independently written reference state model. Include overlapping actors, repeated states, equal/backward timestamps, partial selection and unknown snapshots. `rollback` followed by `restore` should reproduce the original reference world only when every selected step is eligible, no conflicts occur and all apply outcomes verify. Do not assert that property for partial, side-effecting or ambiguous operations.

## Coverage matrix

| Area / requirements | Required cases and oracle |
| --- | --- |
| Capture — CAP-1/3 | Cancel at each legitimate priority, deny build, no-op, ordinary break/place, replacement, cancelled multi-place, same-tick `A -> B -> A`, superclass/subclass routing. Compare actual world transitions and ordered rows, not just callback counts. |
| Attribution — CAP-2 | UUID/name change, ambiguous name, entity/environment source, explicit derived source, unknown player. Unknown remains unknown; proximity never becomes direct attribution. |
| Snapshots — CAP-1/3 | Round-trip full block data, supported metadata/PDC and item components; unsupported codec version, corrupt/truncated/oversized payload. Reject unsafe recovery rather than substitute defaults. |
| Inventory — M4 | Shift-click, drag, swap, double-click, creative edit, double chest, two viewers, hoppers in same tick, partial/full target, cancelled move, pickups and chains. Compare per-slot contents and total item identities/quantities with expected legitimate crafting/drop effects. |
| Environmental — M4 | Edited/cancelled explosion lists, affected groups, fire ignition versus burn, waterlogged/fluid destinations, bucket cancellation, spread/growth/decay, piston source/destination, entity changes. Record only qualified evidence. |
| Queries — QRY-1/2 | Exact position, world isolation, negative X/Z, radius edge, vertical range, UUID/name ambiguity, UTC interval boundaries, malicious/invalid input, empty/incomplete results and combined filters. Compare to a brute-force seeded dataset. |
| Pagination — QRY-2 | Equal timestamps, inserts between pages, cursor expiry, permission change, pruning, cancellation and query deadline. No duplicate/omitted fixed-boundary rows or leaked result ownership. |
| Permissions / UI — QRY-1, RB-1, OPS-2 | All nodes denied by default, console scope requirements, inspect interaction cancellation, untrusted formatted text, completion privacy, permission revoked while async work runs, failed reload retains prior config. |
| Persistence — STO-1/2 | Empty DB, reopen, writer/reader concurrency, group atomicity, uncertain commit/retry, unique-key payload mismatch, busy lock, native library load failure, all migration paths from retained fixtures. |
| Backpressure — STO-3 | Slow/failed writer, record and byte limits, huge group, pending-snapshot expiry, control-capacity pressure, rejected admission, recovery. Validate bounded memory and persistent/unclean coverage warnings. |
| Rollback — RB-1/2 | Reverse order, unselected later/intervening change, identical final state with intervening edits, stale preview, expired/foreign preview, permission loss, duplicate confirmation, partial filter cutting a dependency, missing world/chunk and unsupported state. |
| Apply — RB-3 | Intent acknowledgement required, codec failure before/during/after mutation, post-apply mismatch, delayed side effects, scoped generated-event deduplication, unrelated nested events, budget yield, storage failure and world unload. |
| Restore — RB-4 | Full and partial rollback, skipped/failed/unknown steps, duplicate restore, later user edit, earlier restore, expired restoration rights and missing dependencies. Compare only exact eligible source steps. |
| Retention / backup — OPS-3 | Prune cutoff boundaries, active-plan pins, unresolved-operation pins, explicit restoration expiry, bounded deletion, blocked writer, backup reopen and independently restored world/database. Missing history stays visible. |

Build a real Paper fixture matrix per block/action type. Start with ordinary inert blocks. Add beds/doors, supports, signs, containers, fluid-sensitive or redstone blocks only when their full capture/application contract has dedicated tests. Document exclusion as an outcome; a partial test is not support.

## Interrupted-write and shutdown tests

Use a child JVM with an external controller that knows the input sequence and receives commit acknowledgements. Terminate at controlled boundaries: before admission, after admission, during a batch, immediately before commit, after commit but before acknowledgement, after acknowledgement, and during shutdown drain. Reopen using the pinned driver and reconcile capture keys with the controller's transcript.

Expected results:

- Committed batches are intact; an in-flight transaction is either absent or fully present, never half a logical group.
- Unacknowledged commits can be retried idempotently. Payload mismatch under an existing key is a visible integrity failure.
- Volatile queued events may be absent after termination. The previous session remains unclean and status exposes uncertain coverage; do not require an unknowable exact lost count.
- Clean stop drains all accepted records and records any known rejected interval. Deadline expiry leaves an unclean session, accurate live pending counts in diagnostics, and no false success.
- Workers need no server-thread work to close; cancellation never deadlocks shutdown.

Inject disk-full/read-only/I/O errors in a controlled test environment and through a deterministic storage seam. Test real SQLite locking with another connection. Verify failed startup/migration never replaces the existing DB or falls back silently to a fresh file. Keep a deliberately corrupt fixture copy to verify refusal/diagnostics; do not corrupt original fixtures.

An application kill does not simulate a power outage: the OS can still flush pages. Label process-crash evidence separately from power-loss evidence. If a durability claim requires power-loss validation, use a disposable VM/storage fault environment with documented filesystem/cache behaviour. SQLite's documented sync guarantees are a dependency, not proof that arbitrary hardware honours them.

## Interrupted rollback and restoration

Run both the pure journal model and real-server crash fixtures at each point below:

| Interruption | Required recovery observation |
| --- | --- |
| Before intent commit | No world mutation permitted; absent/unprepared step cannot execute |
| After intent commit, before world mutation | Durable expected/desired states exist; restart requires reconciliation |
| During a multi-target group | Already changed members remain individually identifiable; group cannot be reported wholly successful |
| After mutation, before outcome commit | World may match desired state but completion is uncertain; no blind reapply |
| After outcome commit, before world save | DB may say applied while reloaded world still matches expected; acknowledged steps must also be audited |
| After clean plugin disable, before server world save | A clean logging session is not a world-save checkpoint; retained operation effects require revalidation before reuse |
| After world save, before operation summary | Step evidence is retained; summary is rebuilt without repeating effects |
| During restoration | Only verified source steps qualify; retry cannot duplicate the inverse |
| After independently restoring DB or world backup | Mismatched timelines are surfaced; equality alone does not authorise replay |

Test the expected, desired and unrelated live-state branches for every uncertain step. Include state returning to the same bytes through unrelated changes. Assert that restart performs no automatic uncertain mutation and that a reviewable reconciliation report includes the evidence and limitations.

For world comparisons, record canonical full state plus qualified metadata before and after; inspect dependent neighbours, drops, entities and inventories where relevant. Block recovery must generate no new drops/XP itself. Previously mined items remain outside its scope; a block recovery test must not claim global item conservation. Inventory restoration stays excluded until its separate conservation/recovery model exists.

## Performance methodology

Use synthetic records for storage/planner saturation and real event workloads for server overhead. Synthetic enqueue throughput is not end-to-end plugin performance. First establish a baseline with Chronicle disabled, then recording, then concurrent lookups, then recovery. Hold world, workload, server settings and machine conditions constant.

Record CPU, memory, disk/filesystem, OS, JDK/GC/heap, Paper build/checksum, Chronicle revision, SQLite/JDBC versions, schema/indexes, full config, dataset seed/size/distribution, duration and background load. Separate warm/cold cache behaviour. Use warmup and repeated measured runs; report variation and p50/p95/p99 where sample size supports them. Store raw results and instructions alongside any report when benchmarks exist.

Suggested workload ladder: 10,000, 1 million and 10 million changes, then larger histories only where resources allow. These sizes exercise small history, growth and index scaling; they are test inputs, not capacity claims. Combine uniform spatial history with hot chunks/actors, metadata-heavy records, bursts of grouped events, long-lived readers and constrained storage. Increase rate until the writer saturates and verify overload behaviour instead of hiding it with an unbounded queue.

| Indicator | Measure | Why it matters |
| --- | --- | --- |
| Capture overhead | Callback/reconciliation elapsed time and allocation bytes per eligible event; aggregate per tick | Main-thread cost and GC pressure |
| Database throughput | Durably committed records/groups per second, batch size and commit latency | Includes transactions/sync, not just queue insertion |
| Backlog | Queue count, accounted bytes, oldest pending age, rejected/uncertain count, time to drain | Reveals saturation and actual crash-loss exposure |
| Query cost | End-to-end and DB p50/p95/p99, result/candidate counts, query plan, timeout rate | Separates user latency from SQL and checks index utility |
| Server impact | MSPT distribution, long-tick frequency, CPU and GC with/without Chronicle | Captures effects beyond callback timings |
| Recovery work | Steps/time per tick, longest single mutation, completion time, skips/reconciliation count | Validates scheduler limits and safety trade-offs |
| Storage growth | DB/WAL size per recorded change, checkpoint delay, backup/prune cost | Predicts operational space and maintenance needs |

Paper's [scheduler guide](https://docs.papermc.io/paper/dev/scheduler/) describes a nominal 20 ticks per second (50 ms per tick). The proposed 2 ms recovery slice is a conservative starting allowance within that tick, not a proven overhead target. Count limits complement it because a single API call can exceed the time budget. Test yield decisions with a fake monotonic clock and measure actual overruns on Paper.

Before M5 completion, choose numerical regression budgets from the measured baseline, variance and a documented operator workload. Record why each threshold is meaningful. Shared CI runners may enforce deterministic memory/count/invariant limits while dedicated hosts run timing gates. Do not make an unstable single timing assertion a release blocker or silently ignore a repeatable regression.

Any comparison to another plugin needs equivalent event coverage, durability, retention, hardware, configuration and query/recovery workload. Publish limitations and raw measurements; no superiority claim is justified today.

## CI, regressions and release evidence

M1 CI should compile and run pure logic plus file-backed SQLite tests on Java 25, with at least Linux and Windows coverage for native loading and path/lifecycle behaviour. Add packaging checks when the distributable exists. Real Paper jobs pin downloads/checksums, use fresh fixtures and have explicit EULA/runtime setup. If a runtime check is temporarily manual, record its procedure/result and require it before release rather than calling it automated.

Run longer fault/stress suites on a schedule and for changes to capture, persistence, snapshots or recovery. Attach logs, seeds, expected/actual state differences, operation journals and sanitized database fixtures on failure. Keep sensitive production data out of CI artifacts. A defect fix includes a reproducer that fails before the fix and a durable regression test at the appropriate layer.

Release validation requires:

1. All relevant requirements and advertised capabilities mapped to passing tests on the exact supported runtime matrix.
2. Packaged JAR installation and upgrade/restart checks from a clean machine/server; dependency resources and license notices intact.
3. No unresolved silent data loss, corruption, unsafe recovery, item duplication introduced by the executor, or permission bypass defect in supported paths.
4. Verified backup/restore, migrations, retention, overload visibility, bounded shutdown and interrupted-operation reconciliation.
5. Performance reports and reasoned defaults/limits, including known exclusions and regression decisions.
6. A staff member able to follow the documented investigation, preview, confirm, restore and recovery workflow without developer knowledge.

The release evidence must name the artifact/revision, runtime versions, tests run, manual results and remaining limitations. Update public support claims only after this evidence exists.
