# Storage and operations

The development snapshot stores [qualified stone/air player transitions](CAPTURE_QUALIFICATION.md) and operation-origin audit rows. M2 provides [lookup and inspection](INVESTIGATION.md); M3 adds [inert-block recovery](RECOVERY.md). Pruning and backup commands remain unavailable. The published evaluation alpha remains M1/M2 only. Use disposable servers while evaluating development builds.

## Database and lifecycle

The local file is `plugins/Chronicle/history.db`. Xerial SQLite JDBC **3.54.1.0** bundles SQLite **3.54.1** and its native libraries. The pin was checked against [Maven Central metadata](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml) on 11 November 2026. The packaged driver is loaded with an isolated platform-parent classloader: the pinned Paper server supplies SQLite 3.49.1, which must not override Chronicle's driver. Native resources and upstream license notices remain intact in the JAR. See [third-party notices](../THIRD_PARTY.md).

One named writer owns its connection, native loading, schema checks, transactions and close. Two bounded query workers own separate read-only/query-only connections and short transactions, with VM/deadline cancellation. All three isolate the pinned driver from Paper's older JDBC. An exclusive sibling `.lock` file prevents another Chronicle writer process from taking ownership; the file's existence alone is not a held lock. Other tools can still acquire SQLite locks. Capture performs no JDBC/file I/O or blocking future waits. Configuration loading is ordinary plugin startup work; shutdown is the sole bounded server-thread writer wait. Reader close runs on the owning workers.

`STARTING` precedes schema inspection and a durably unclean session. The worker verifies SQLite >=3.51.3, WAL, FULL synchronous mode, foreign keys and the configured busy timeout. It refuses unknown/newer schemas, changed migration checksums, partial/unversioned databases, corrupt input and unavailable storage. It never substitutes an empty database. `READY` enables capture; errors leave status at `FAILED`. The exact qualified Paper build/data version is checked before starting storage. Storage/reconciliation changes require restart; only presentation settings can reload.

Schema 1 contains `schema_migrations`, `sessions`, `worlds`, `actors`, `capture_groups`, `changes` and `coverage_incidents`. Its SQL resource and recorded SHA-256 remain unchanged. The forward migration to schema 2 adds explicit actor kind/cause/attribution quality, observed player-name aliases and query indexes. Existing M1 rows have known direct-player attribution by their original contract; migration leaves their IDs, snapshots and canonical payload bytes intact. Empty-to-v2 and v1-to-v2 upgrades are transactional, with a separate checksum for each numbered SQL resource and `user_version=2`. Failure rolls back all pending columns, tables and ledger entries; unknown partial input is refused. Applied SQL must not be edited after release; future changes need new migrations. Stop and back up before evaluating an upgrade; M1 binaries cannot open a v2 database.

Current schema **3** appends `operations`, `recovery_steps`, `recovery_effects` and
operation/step links on `changes`. Empty/v1/v2-to-v3 upgrades retain the earlier
migration resources and are transactional. Tests retain IDs, snapshots, aliases and
canonical payloads, including failed-upgrade rollback/reopen. The published alpha
cannot open schema 3; there is no downgrade command. Back up stopped-server data
before upgrading.

Recovery controls use one bounded slot on the existing writer, after ordered
capture drains. Durable intents precede mutation. Actual outcomes, operation-origin
history and effective restore claims commit atomically. The original rows and
`recovery_eligible=0` capture flags are unchanged; recovery uses a separately
qualified planner. Operation audit rows explicitly carry `SYSTEM / OPERATION / EXACT`
attribution and an operation ID. The legacy identity table remains schema-compatible;
its original kind column does not override per-change attribution. Synthetic
operation identities are not claims of direct-player causation.

World/player UUIDs are stable identities. A group is `(session_id, capture_sequence)`; members have unique indexes. AUTOINCREMENT session/history IDs are never reused. Timestamps are UTC milliseconds, separate from causal capture order. Rows retain action, coordinates/chunks, actor name, canonical before/after block data, codec/data versions and evidence source. Schema 2 records `PLAYER`, `PLAYER_ACTION`, `DIRECT`; unexpected attribution or snapshot versions are labelled by readers. Every row has `recovery_eligible=0`. Exact canonical payload comparison detects reuse of a key with different contents, including group size and metadata. A conflicting batch rolls back as a whole, including aliases. SQL, unchecked and native exceptions trigger explicit rollback; auto-commit is restored only after commit or successful rollback, so resetting the connection cannot commit a partial group.

## Bounds and failure behaviour

Default settings are development bounds, not capacity/latency claims:

| Setting | Default | Hard configuration range |
| --- | --- | --- |
| `storage.max-records` | 10,000 | 1–100,000 |
| `storage.max-bytes` | 33,554,432 | 1,024–268,435,456 |
| `storage.batch-records` | 500 | 1–queue record bound |
| `storage.batch-bytes` | 1,048,576 | 512–queue byte bound |
| `storage.flush-millis` | 100 | 1–1,000 |
| `storage.busy-millis` | 1,000 | 1–5,000 |
| `storage.shutdown-millis` | 10,000 | 1–60,000 |
| `storage.reconciliation-records` | 1,024 | 1–10,000 |

Existing version-1 bootstrap configs can omit the new storage keys and use these defaults. Explicit values must be YAML integers; unknown keys, invalid bounds and batches larger than queue bounds fail validation. A record has a 256 KiB accounted-payload ceiling and a group at most 10,000 members; whole groups must also fit queue limits. M1 capture emits single-member groups. Accounted payload includes fixed overhead and copied string characters; this is not a bound on the entire JVM/native heap. Reconciliation is separately count-bounded and retains only the tiny qualified snapshots.

Queue accounting includes queued and in-flight work until commit acknowledgement. Whole groups are never split to fit a normal batch target; an oversized target group uses a standalone transaction within hard admission limits. The worker wakes on admission and at the flush interval; the interval is not an intentional minimum delay. No accepted work is evicted to admit newer records. Overflow rejects the new group, increments counters and latches coverage. Incidents use one cumulative aggregate per reason/session, with global scope and first/last time/sequence; control memory cannot grow per rejected event.

BUSY/LOCKED retries use identical immutable content, at most three attempts, the finite busy timeout and 25/50 ms backoff. Non-transient I/O, integrity and exhausted-lock failures stop admission and leave accepted work explicitly pending/uncertain. There is no automatic writer restart or durable ingress spool. A commit with a lost acknowledgement can already be present; retrying the same key/content cannot duplicate it. After a fatal failure, preserve the files, resolve the cause and restart. The previous unclean session warns conservatively about possible missing history, even if some unacknowledged work committed.

Shutdown stops capture, labels unresolved observations ambiguous and drains accepted work within the configured deadline. A clean session is sealed only after history and known incidents commit. Timeout returns without waiting indefinitely for a stalled native call; the worker retains ownership until it can finish/close, and no new writer may bypass that lock. In-flight work may commit after a reported timeout. A late seal is revoked when the worker regains control; crash/acknowledgement uncertainty must be treated conservatively. A process kill cannot preserve an exact count of memory-only observations. Tests exercise blocked writes and process death; they do not simulate arbitrary hardware hangs or power loss.

## Reading status and preserving data

`/chronicle status` requires `chronicle.status` for players, including operators; console is trusted. It reports readiness, schema/engine/session, accepted and committed counts, committed capture/history positions, reconciliation backlog, observed sequence, queued/in-flight count/bytes/age, rejected admissions, current incidents and counts of prior unclean sessions/incident aggregates. Runtime detail display and lookup page size can reload. Status also exposes reader admission/completion pressure; lookups repeat boundary/lag/coverage warnings. A committed sequence is a durability position, not a promise of gap-free coverage. Unknown/unsupported history is never represented as complete.

`STARTUP` marks the initialization interval. `UNSUPPORTED`, `AMBIGUOUS`, reconciliation/queue overflow, storage failure, timeout and previous unclean sessions explain known gaps. Prior incidents survive a clean restart. If storage is unavailable, current exact counts may exist only in memory; the durable unclean flag remains the restart warning. Console warnings are emitted by the worker without capture I/O. Gameplay continues during degradation.

There is no automatic deletion or pruning. Back up only after stopping the server and verifying it exited; preserve the database and any accompanying WAL/SHM files as a set, then verify the copy opens in a disposable environment. Do not copy only a live `.db`, delete a WAL, or replace corrupt storage to make status appear healthy. Network filesystems are outside the supported design. A clean plugin session is **not** a Minecraft world-save checkpoint, and database/world backups are not an atomic recovery point.
