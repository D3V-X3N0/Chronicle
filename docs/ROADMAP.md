# Development roadmap

Status: the first build is implemented and Milestone 1 is in progress; no milestone is complete. There are no delivery dates. Dependencies and evidence determine progress. The [product specification](PRODUCT_SPEC.md) defines what may eventually be advertised, and [architecture](ARCHITECTURE.md) records the proposed design and open decisions.

The main path is **M1 -> M2 -> M3 -> M5 -> M6**. M4 builds on M2's capture contracts and uses M3's safety model for any recovery research. Expanded families can be promoted separately after their tests pass; M4 is not permission to delay core correctness until M5. Tests, diagnostics, and basic failure handling start in M1.

## First development task

Scope: **Bootstrap a reproducible Paper 26.3 / Java 25 development harness and establish the ordinary-block capture contract.**

Implementation plan and current progress:

1. **Implemented:** Gradle 9.9.1 with verified Wrapper/distribution, Java 25 toolchain, Paper API `26.3.build.248-stable`, matching server checksum, dependency locks and SHA-256 verification.
2. **Implemented:** a single-project Kotlin DSL build, `plugin.yml`, lifecycle entry point, strict configuration and a permission-gated status command under `io.github.d3vx3n0.chronicle`. Both the main JAR and a separate diagnostic-fixture JAR build successfully.
3. **Implemented and locally verified:** 23 passing JUnit tests, packaged-artifact checks, an identical offline JAR rebuild, and successful Paper lifecycle/synthetic-fixture/invalid-configuration runs. Linux/Windows CI is configured; remote execution is not yet verified. Evidence is tracked in [TESTING.md](TESTING.md#current-build-evidence).
4. **Synthetic fixture passed; real-player qualification pending:** controlled stone placement/breaking, cancelled/denied attempts and repeated same-tick changes. The real Paper fixture dispatches synthetic player events; a real-player capture-contract investigation remains necessary before production logging. No rollback or universal state codec has been started.

Completion evidence: a clean checkout builds with only the documented JDK and Wrapper; the plugin loads/unloads in the disposable server; no production world is used; cancellation yields no successful-change record; the observation report identifies any unprovable intermediate state. This provides a reproducible foundation and removes the riskiest assumption before schema design hardens.

The first build records diagnostic attempts only in the separate fixture, including cancelled attempts. It creates no successful-change history records. The next implementation step is to complete actual player/protection-plugin observation on the pinned runtime, document eligible transitions and uncertainty, then add the SQLite lifecycle/schema/write queue. M1 remains incomplete until the exit criteria below pass.

### Tracked foundation work

The [Milestone 1 tracker](https://github.com/D3V-X3N0/Chronicle/milestone/1) contains the concrete remaining work. These issues are open; the bootstrap commit does not close them.

| Issue | Dependency |
| --- | --- |
| [#1: Qualify real-player block placement and break capture](https://github.com/D3V-X3N0/Chronicle/issues/1) | Existing diagnostic fixture; actual clients/protection-plugin evidence still needed |
| [#2: Implement SQLite lifecycle and versioned foundation schema](https://github.com/D3V-X3N0/Chronicle/issues/2) | Final history-record semantics follow #1; driver/lifecycle preparation can proceed independently |
| [#3: Implement bounded transactional history writer and failure handling](https://github.com/D3V-X3N0/Chronicle/issues/3) | #2 and the capture semantics from #1 |
| [#4: Connect qualified block capture to durable history and status](https://github.com/D3V-X3N0/Chronicle/issues/4) | #1, #2 and #3 |

## Milestone 1 — Foundation and capture contract

**Depends on:** the documentation baseline and the first task above.

Tasks:

- Establish source/package boundaries and constructor wiring, validated configuration, readiness states, and safe lifecycle cleanup.
- Select and package a maintained SQLite JDBC driver; verify native loading on Windows and Linux, engine version, WAL and FULL settings.
- Implement schema version 1, migration checksums, stable identities, sessions, capture keys, history records and coverage incidents.
- Add bounded reconciliation/admission queues, a single writer, transactional prepared batches, commit acknowledgement, and observable overflow/error states.
- Capture a small qualified subset of direct player placement/breaking. Mark unsupported states inspect-only or unsupported with reasons.
- Add database integration and unit tests as each contract appears. Create the build/test CI workflow and document actual task names.

Exit criteria:

- A fresh database initialises, closes and reopens without changing committed records; a newer schema or invalid config is rejected without destructive reset.
- Supported fixture changes have correct UUIDs, coordinates, order, old/new snapshots and unique keys; cancelled/denied changes do not appear as successful.
- Retrying an uncertain commit cannot duplicate a row. A failed batch cannot leave half a group stored.
- Overflow, storage failure and a forced shutdown timeout are visible; memory remains bounded, and the session is not falsely marked clean.
- A clean shutdown drains accepted work within its configured deadline in the test workload. Real-server checks demonstrate no database I/O in capture callbacks.

**Risk gate:** if public events cannot establish a reliable transition for a proposed type, narrow its eligibility instead of claiming exact logging. This decision is required before M2 expands capture.

## Milestone 2 — Functional logging and investigation

**Depends on:** M1 identity, capture, durability and migration contracts.

Tasks:

- Stabilise the supported block/state list, name-to-UUID lookup, explicit actor/cause quality, and snapshot version checks.
- Implement inspection, lookup filters, permissions, tab completion, bounded asynchronous queries and stable cursor pagination.
- Surface committed boundaries, lag, coverage gaps and startup/unclean-session warnings in lookup and status.
- Measure candidate indexes on representative history sizes while writes continue.
- Add multi-plugin cancellation tests, same-tick order fixtures, negative-coordinate/radius boundaries and restart persistence tests.
- Implement reload only for validated runtime-safe settings; document restart-required settings.

Exit criteria:

- An administrator can investigate a scripted griefing fixture from a clean setup, restart the server, and obtain the same committed history.
- Filter results exactly match an independent reference dataset; pagination neither duplicates nor omits fixed-boundary rows when new rows arrive.
- Unknown or incomplete history is labelled; missing permission denies both commands and asynchronous results.
- Inspection does not mutate the world or open inventories on its qualified interactions, and does not bypass protection cancellation.
- High-volume recording with simultaneous lookups respects memory limits, exposes backlog, and keeps world API calls on the server thread. Initial measurement results are recorded without performance-superiority claims.

## Milestone 3 — Safe rollback and restoration

**Depends on:** M2 trustworthy ordered history and query boundaries; starts only with qualified reversible state codecs.

Tasks:

- Implement pure planning with bounded frozen selections, dependency/conflict detection, preview expiry and idempotent confirmation.
- Add operation/step migrations, durable intents, actual before/after snapshots, explicit outcomes and audit links to original changes.
- Build a server-thread executor with time/count budgets, loaded-chunk checks, scoped event correlation and one active operation.
- Implement restore by rollback ID with exactly-once effective step eligibility; never rerun original filters.
- Add pause/progress reporting, restart reconciliation views, and explicit operator-reviewed continuation/compensation plans.
- Test partial failures and world-save divergence before widening eligible blocks.

Exit criteria:

- Reference worlds match expected states after eligible rollback and restoration, while unrelated edits remain untouched.
- Stale previews, intervening unselected edits (including changes back to identical state), unsupported metadata and known coverage gaps prevent unsafe writes.
- A duplicate confirmation or retried outcome cannot apply a step twice; generated history is attributed to the operation exactly once.
- Killing the process at every journal boundary produces an explainable persisted state. Restart performs no automatic uncertain mutation; even acknowledged steps are audited when world saves may lag.
- Partial operations list exact applied/skipped/unknown outcomes; restore never includes a skipped or unverified step.
- Per-tick work and single-call overruns are measured on a real server; no synchronous chunk load/generation or blocking database wait occurs in execution.

**Risk gate:** inventory restoration, arbitrary block entities and unsafe physics remain excluded unless a separate complete state and side-effect model passes tests. The initial engine can ship with a documented inert-block subset.

## Milestone 4 — Expanded logging, one event family at a time

**Depends on:** M2; any expansion of recovery also requires M3's complete correctness suite.

Investigate in this order to expose dependencies early:

1. Explosion affected-block grouping and entity/source attribution; explicit cancelled and edited block lists.
2. Supported container slot deltas, full item metadata and double-chest identity; cancellation and multiple-viewer ordering.
3. Hopper movement and pickup, partial insertion, item conservation, chained transfers, and server configurations that suppress events.
4. Fluid/bucket/sponge, fire, growth/decay, piston and selected entity-related changes, with per-family causality limits.

For each family, define its event contract, actual state evidence, volume limits, codec support, privacy/permission impact and runtime fixtures before adding an enabled-by-default setting.

Exit criteria per promoted capability:

- A published matrix identifies exactly which event/inventory types and server settings are supported, inspection-only, or unsupported.
- Test fixtures cover success, cancellation, duplicates, no-op/partial effects, overlapping activity, overload, and restart persistence.
- Inventory fixtures compare full item data and quantities; ambiguity stays unknown rather than being assigned to an arbitrary player.
- A recorded event is not described as reversible unless its recovery suite also passes.

An unproven family is explicitly deferred or kept disabled/experimental. M6 may proceed with the core M1–M3 release scope after those deferrals are recorded; it must not imply all M4 families are complete.

## Milestone 5 — Performance and operational reliability

**Depends on:** a working M2/M3 path and whichever M4 capabilities are release candidates. Baseline measurements start earlier.

Tasks:

- Profile capture allocations/time, committed throughput/latency, queue pressure, query latency, WAL growth and rollback tick impact.
- Run isolated and mixed workloads with long histories, hot regions, metadata-heavy records, slow storage and blocked readers.
- Tune batch limits, indexes and operation budgets from reproducible results. Retest correctness after tuning.
- Complete disk-full, driver error, busy lock, process kill, shutdown deadline and corrupted-input exercises.
- Implement explicit retention/restore-expiry policy, bounded pruning, verified backups, migration recovery and actionable diagnostics.
- Document capacity constraints and practical defaults for measured environments; define regression budgets with a reasoned baseline.

Exit criteria:

- Reports include hardware, OS/filesystem, exact builds, JVM/heap, dataset seed, configs, durability mode, warmup and repeated measurements.
- Every observed supported event is accounted for as committed, pending or explicitly rejected/uncertain; no unexplained loss, duplicate transition or incorrect restore remains.
- Long mixed-workload runs stay within configured memory bounds, reveal write saturation, and recover from recoverable faults without hiding coverage gaps.
- Backup restore, migration failure and pruning preserve recovery dependencies; numerical defaults and regression thresholds are justified by results.

## Milestone 6 — First public release

**Depends on:** M1–M3 and M5 gates, plus passing gates or explicit deferrals for M4 families. Public-release work has not started.

Tasks:

- Validate the exact supported Paper/Minecraft/Java and OS matrix on clean servers, plus representative protection/plugin interactions.
- Test the distributable JAR, dependency packaging/native loading, install/upgrade/disable/restart and documented operator workflows.
- Update README feature claims from test evidence; supply actual commands, configuration, compatibility limits and recovery/backup instructions within the existing documentation.
- Add versioned release packaging, checksums, dependency/license notices, CI release gates and release notes. Preserve MIT attribution.
- Review contribution/issue guidance and ensure no unresolved data-corruption, silent-loss, unsafe-restore or permission defect remains.

Exit criteria: a new administrator can reproduce installation, investigation, previewed rollback, restore, degraded-status interpretation and restart reconciliation from the documentation; all required checks pass for the exact artifact; every advertised capability has evidence. A developer must be able to reproduce the checks from a clean checkout.

Publishing a release is a later owner-authorised action. This roadmap neither creates releases nor manufactures activity or programme eligibility. Post-release priorities should follow actual incident reports and measurements.
