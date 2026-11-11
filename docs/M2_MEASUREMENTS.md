# M2 synthetic storage and investigation measurements

This is an initial development measurement, not production capacity guidance or a
comparison with another plugin. `HistoryLoad` exercises the real immutable records,
SQLite writer and reader predicates, but does not create Minecraft events. Paper
interaction/restart evidence is separate in [TESTING.md](TESTING.md#current-m2-evidence).

## Reproduce

With JDK 25 and the pinned Wrapper, from the repository root:

```powershell
.\gradlew.bat historyLoadTest
```

```sh
bash ./gradlew historyLoadTest
```

The default starting sizes are 10,000, 100,000 and 1,000,000 rows. A shorter diagnostic
run can use `-PhistorySizes=10000`. Each run creates fresh disposable databases and
`measurements.tsv` beneath `build/load-tests/history-*/`. Nothing opens a production
database. Preserve needed reports before `clean` removes generated files.

## Environment and protocol

Measured 11 November 2026 on Windows 11/NTFS, AMD Ryzen 9 7950X (16 cores/32 logical
processors), 67,808,509,952 bytes reported physical memory, Crucial CT4000T700SSD3
NVMe storage for the repository's C: volume. Oracle JDK 25.0.1+8-LTS-27, G1, 512 MiB
maximum measurement JVM heap; Gradle 9.9.1, Xerial JDBC 3.54.1.0 / SQLite 3.54.1,
schema 2, WAL and FULL synchronous durability. This was a development desktop with
ordinary background activity, not an isolated benchmark host. No Paper process ran
during the final measurement. Paper 26.3 build 248 is used only by the separate
runtime checks and is pinned by `gradle/paper-test.properties`.

Generator v1 is a deterministic modular sequence, with no PRNG: 100 actors, two
worlds (80/20 distribution), 25% hot-column changes at X=-17/Z=-1, remaining X/Z
spread across a 2,048-block square, heights -64..319, alternating place/break and
timestamps cycling across 10,000 milliseconds. The exact formula is in
`src/test/java/io/github/d3vx3n0/chronicle/storage/HistoryLoad.java`. These are
synthetic history rows, not a physically consistent Minecraft world simulation.
Independent arithmetic selects expected newest IDs for actor, world, radius-20
circle and narrow-time queries; every measured/warmup query must match those IDs.

Seed batches contain at most 500 single-member groups. All connections close before
copying the checkpointed baseline, so each index variant starts with identical
history. Query ceilings remain fixed at the seed ID while newer writes arrive.
The candidate sets, in addition to the immutable capture-key and exact-position
indexes, are:

| Variant | Additional `changes` indexes |
| --- | --- |
| position-only | None |
| ordered | `(actor_uuid,id)`, `(world_uuid,id)`; selected M2 baseline |
| time | `(actor_uuid,occurred_at,id)`, `(world_uuid,occurred_at,id)`, `(occurred_at,id)` |
| hybrid | Ordered indexes plus `(occurred_at,id)` |

Each variant runs `ANALYZE` and records `EXPLAIN QUERY PLAN`. Plans are diagnostic
text, never parsed by production code. One reader performs ten warmups then three
rounds of thirty measured queries per scope, ten rows per page. Caches are warm;
there is no OS cache flush or cold-cache claim. Each request retains the normal
two-second/approximately ten-million-VM-instruction budget. The TSV records each
round's p50, p95 and maximum; with only thirty samples, reported p99 equals maximum
and is not a reliable population tail estimate.

Meanwhile a producer attempts bursts of 500 records with a 1 ms pause between
bursts. The writer queue is capped at 2,000 records/4 MiB accounted bytes, normal
batches at 500/1 MiB, flush 100 ms, busy 1,000 ms, shutdown 10 s, reconciliation
setting 1,024 (unused by this synthetic producer). These deliberately smaller
record limits exercise rejection; they are not proposed production defaults.
Every attempt must equal accepted plus rejected; every accepted row must commit,
drain, and match an independent SQL count for its session. Queue count/bytes are
asserted throughout. Rejection is explicit coverage loss, not successful capture.
The report includes observed backlog/age, final database size and post-close WAL
size; the latter is not a peak WAL-growth measurement.

## Results and interpretation

**Passed:** all three sizes and all four variants in `history-14617695897585352311`.
The [per-round TSV](evidence/m2-history-load-2026-11-11.tsv) preserves 4,320 measured
queries plus plans, startup and writer counters (480 additional warmup queries also
matched the oracle). No measured query exceeded its budget or returned a wrong row.
The full task took 4 minutes 58 seconds, including seed construction and index setup.

For the selected ordered indexes, the range of the three per-round p50 values was:

| Starting rows | Actor lookup (ms) | World lookup (ms) | Circle lookup (ms) | Narrow time lookup (ms) |
| --- | --- | --- | --- | --- |
| 10,000 | 0.117?0.133 | 0.114?0.117 | 0.144?0.147 | 3.026?3.222 |
| 100,000 | 0.109?0.174 | 0.093?0.098 | 0.120?0.130 | 6.851?6.984 |
| 1,000,000 | 0.095?0.106 | 0.125?0.193 | 0.252?0.255 | 7.118?7.984 |

At one million rows, the time-oriented actor index required a temporary ordering
B-tree and had actor-lookup p50 63.588?69.544 ms, while its narrow-time p50 was
0.564?0.652 ms. The hybrid retained fast actor keyset access and reduced narrow-time
p50 to 0.719?0.749 ms. The ordered variant had a 226.829 ms narrow-time maximum
outlier; most of those samples were below 10 ms. This desktop run is too small to
attribute that outlier or define a timing regression budget. The common-world and
hot-circle plans frequently chose primary-key scans after `ANALYZE`; this workload
does not establish the world-ID index's value for rare worlds.

All twelve mixed runs reached their 2,000-record bound, with maximum accounted
payload 1,439,600 bytes. Maximum observed oldest pending age was 706 ms. Every
accepted row drained and matched its durable session count; all other attempted
admissions were explicitly rejected. For the one-million-row ordered run, 399,000
attempts produced 7,517 committed and 391,483 rejected rows, with zero pending at
clean stop. This intentionally saturated burst is **not** a sustainable throughput
number. Final database files ranged with workload/index choice; dropped-index free
pages can remain allocated, so file size is not an index-only space comparison.
Post-close WAL files were absent; peak WAL growth was not measured.

Million-row writer startup took 10.632 seconds in the first position-only variant
and 1.475?1.605 seconds in subsequent variants with warmer caches. An earlier run
had stopped at the harness's ten-second startup allowance during integrity checking.
The measurement harness now allows 120 seconds and records actual startup time.
Production integrity validation and capture readiness were not weakened; operators
can see `STARTING` during this interval.

The selected ordered indexes favour history-ID keyset access. M2 retains them as the initial baseline; adding a global time index is deferred to M5 workload/capacity tuning rather than claiming the hybrid is universally preferable. Time-oriented
alternatives can improve selective time windows but introduce ordering work and
extra write/storage costs. This initial workload does not establish a universal
index winner. M5 must evaluate longer mixed runs, uncommon worlds/actors, larger
histories, cold caches, disk faults, allocation profiles and real Paper MSPT before
claiming capacity or selecting timing regression budgets.

Memory evidence covers explicit queue/result/cursor/payload bounds, not the entire
JVM/native heap. The automated query-service tests separately fill both readers,
all 32 waiting slots and all 34 retained completions; server-thread guards and real
packet tests cover Paper ownership. Long native/OS stalls, arbitrary protection
plugins, hardware power loss, recovery, inventory metadata and expanded event
families remain outside this report.
