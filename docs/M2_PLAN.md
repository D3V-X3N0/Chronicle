# M2 implementation plan

M2 builds on completed M1 at `007cd07`. It retains the qualified direct-player
stone/air transitions on Paper 26.3 build 248, Java 25 and SQLite FULL/WAL.
No recovery, expanded event families or release work is included.

## Dependency order and gates

1. **Query contracts and storage:** append schema 2 without changing migration 1;
   retain observed name aliases, label direct player/action attribution, validate
   snapshot versions on read. Add immutable filters, exact radius predicates,
   identity ambiguity errors and committed-ID keyset queries. Compare all filters
   to an independent dataset, including negative coordinates and clock reversal.
2. **Investigation and operations:** two bounded reader workers, deadlines and
   cancellation, bounded completion delivery and requester-owned expiring cursors.
   Implement lookup, inspection, permissions and completions. Recheck permission
   on delivery; invalidate on logout/disable. Reload only fully validated runtime
   presentation settings; reject restart-required changes atomically.
3. **Runtime qualification and load:** extend disposable packet tests with actual
   investigation before/after restart and protected inspection. Measure candidate
   indexes with concurrent writing, enforce count/byte/task bounds, report backlog
   and preserve M1 crash, cancellation and same-tick tests.
4. **Handoff:** record exact tests and limitations, update current documentation,
   commit coherent changes, push normally and verify Linux/Windows CI. Close the
   issues/milestone only after their acceptance evidence exists.

## Selected M2 contracts

- History is ordered by descending committed history ID, not timestamp. Timestamps
  remain half-open UTC filters. This deliberately replaces the architecture's
  proposed timestamp/ID display order: ingestion order also survives equal or
  backwards clocks and matches capture order. Every page fixes an ID ceiling.
- Names resolve from locally stored identities on a reader worker. Historical
  player aliases are retained; ambiguity requires an explicit UUID. No network
  profile lookup or synchronous database completion is used.
- Inspection handles main-hand left/right block clicks, denies both item and block
  use, and never reverses an existing denial. Protected interactions yield no
  history. Container opening and held-item placement are runtime qualification
  gates; container history itself remains unsupported.
- Query memory includes queued, running and completed-but-undelivered tasks.
  SQLite VM progress, elapsed deadlines, result/page sizes, cursor count and
  command input length all have explicit limits. Saturation is a visible error.
- Lookup always explains limited coverage, current lag and committed boundary;
  empty results never establish that nothing happened. Prior unclean sessions,
  startup and incident aggregates remain visible. No query claims a world-save
  checkpoint or gap-free boundary.
- Existing schema-1 records have known direct player attribution by their original
  contract. Unknown versions or unexpected payloads are labelled unsupported for
  interpretation, never converted to air or made eligible for recovery.

M2 is complete for the qualified scope. Actual evidence and measured limits are
recorded in [TESTING.md](TESTING.md#current-m2-evidence) and [M2_MEASUREMENTS.md](M2_MEASUREMENTS.md).
The dependency steps above describe the implementation order, not outstanding work.

## Tracking

- [#5: bounded queries and identity contracts](https://github.com/D3V-X3N0/Chronicle/issues/5)
- [#6: investigation commands and safe reload](https://github.com/D3V-X3N0/Chronicle/issues/6), depends on #5
- [#7: runtime, restart and load qualification](https://github.com/D3V-X3N0/Chronicle/issues/7), depends on #5 and #6

The [operator guide](INVESTIGATION.md), [test evidence](TESTING.md#current-m2-evidence)
and [measurement report](M2_MEASUREMENTS.md) describe the final contracts and limits.
