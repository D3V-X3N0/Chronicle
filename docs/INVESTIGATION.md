# Investigation in the first alpha

Chronicle records only the [qualified direct-player stone/air transitions](CAPTURE_QUALIFICATION.md).
Inspection and lookup read committed SQLite history. They do not recover blocks,
read container contents or establish complete coverage. This is the [unpublished alpha candidate](releases/v0.1.0-alpha.1.md).

## Setup and commands

Use the pinned build and installation procedure in [DEVELOPMENT.md](DEVELOPMENT.md).
Wait for `/chronicle status` to report `READY`. Grant players the specific
`chronicle.inspect`, `chronicle.lookup`, `chronicle.status` and/or `chronicle.reload`
permissions they need. All default to false, including for operators. Local console
is trusted. Inventory, rollback and restore permissions/commands are not implemented.

```text
/chronicle inspect
/chronicle lookup player:Alex time:2h radius:20 world:world action:block-break
/chronicle lookup player:00000000-0000-0000-0000-000000000002 time:1d
/chronicle lookup from:2026-11-11T10:00:00Z to:2026-11-11T12:00:00Z world:world
/chronicle lookup world:world origin:-17,-1 radius:5 action:block-break,block-place
/chronicle lookup page:<cursor-from-output>
/chronicle status
/chronicle reload
```

Names and UUIDs in these examples are placeholders; only identities already stored
in this server's history resolve. Player names match case-insensitively against all
observed aliases, including names backfilled from M1 rows. A reused name can identify
multiple UUIDs: Chronicle rejects it and requires an explicit UUID. World names use
the last stored name; ambiguous names also require a UUID. Resolution uses local
SQLite on a worker, never a network profile lookup. No match produces an identity
error, not a fabricated empty player/world history.

Filters combine with AND. Up to eight comma-separated players combine with OR;
the two comma-separated action identifiers combine with OR. Other filters accept
one value. Duplicate/unknown keys and malformed/oversized arguments are rejected.
The entire filter input is limited to eight arguments and 1,024 characters.

Omitted `time` means `1h`; omitted world, player or action means all stored worlds,
all actors or both qualified actions respectively. Relative time accepts positive
integer seconds/minutes/hours/days (`s`, `m`, `h`, `d`), up to 3,650 days. It resolves
once at submission. Explicit `from` and `to` must both be ISO 8601 UTC timestamps
ending in `Z`, with `from < to`; they cannot be combined with `time`. All intervals
are half-open `[from,to)`, and the resolved interval is displayed.

Radius is an inclusive horizontal circle, 0–1,000 blocks, over every height. The
player's current block X/Z is copied at submission if `origin` is omitted; an
explicit world uses those same coordinates in that world. With no explicit world,
the player's world UUID is copied. Console radius queries require both `world` and
`origin:x,z`. Origins are limited to +/-30,000,000. Negative coordinates and circle
edges use exact squared distances; inspection uses the exact X/Y/Z instead.

## Results, permissions and pagination

Results show history ID, UTC time, action, captured actor name and UUID, world UUID,
position, before/after data, capture identity, observation evidence, codec/data
version and interpretation quality. M1/M2 successful rows have explicit
`PLAYER / PLAYER_ACTION / DIRECT` attribution. Unknown attribution or unsupported
snapshot versions/payloads are labelled; nothing is substituted with air. All rows
remain ineligible for recovery, with no operation link.

Each first page freezes the maximum committed history ID in its short SQLite read
transaction. Results use descending history ID (capture/ingestion order), including
when timestamps are equal or move backwards. Later pages apply the same filters,
fixed ID ceiling and an exclusive last-ID keyset. Concurrent inserts cannot move
those pages. No database transaction remains open between requests.

The opaque next cursor belongs to its requester and original permission. There is
one retained next-page cursor per requester, at most 128 globally. A new successful
page replaces the prior token. Context expires two minutes after initial submission;
paging does not renew it. Existing contexts retain their original page size during
reload. Console contexts are shared by the local console. Cursors are memory-only
and do not survive logout, disable or restart; rerun the resolved scope after restart.
Chronicle performs no pruning in M2, so pruning invalidation is not yet applicable.

Permissions are checked on command/click admission, during pending work and before
delivery on the server thread. Revocation cancels pending work and suppresses its
results. Inspection cursors require `chronicle.inspect`; ordinary lookup cursors
require `chronicle.lookup`. Completed rows are never sent from a reader thread.
Completion suggests permitted commands, action/filter syntax, at most 32 loaded
worlds and a bounded cache of recently displayed player names. It performs no SQL
scan or network lookup and is not an exhaustive directory of historical actors.

## Read-only inspection boundary

`inspect` toggles a player mode. Main-hand left/right block clicks request exact
position history. Both block use and held-item use are denied at `HIGHEST`; offhand
clicks are suppressed without duplicate queries. A prior denial of either use result
returns no history. A break/place guard also cancels an inspector's mutation events.
Mode and outstanding requests clear on logout and disable. Permission loss removes
inspection mode; subsequent gameplay follows normal server rules.

The disposable packet tests cover stone, held stone placement, chest opening,
left-click breaking, offhand and a separate protection plugin's block/item denial.
Protection is not overridden and inventories are never opened/read by Chronicle.
Chest history remains unsupported; inspection only queries the location's existing
stone/air history. This is a qualified interaction set, not every Minecraft use case.
Plugins that reverse denials later at `HIGHEST` or mutate at `MONITOR` violate this
qualification; no universal protection-plugin interoperability is claimed.

The interaction implementation follows Paper's separate
[block/item use results](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerInteractEvent.html).
Runtime fixtures, rather than the API description alone, establish the boundary above.

## Bounds, lag and unavailable history

- Two reader workers, at most 32 waiting tasks and 34 total retained tasks, including
  running and completed-but-undelivered results. One outstanding query per requester;
  at most eight completions are delivered per tick. Rejected admissions are visible.
- Default page size 10, configurable 1–50. Display strings are bounded; a query reads
  at most one extra row to detect another page. SQLite caches are 2 MiB per reader;
  these are not a whole-process/native-heap guarantee.
- Two-second deadline includes queue time. SQLite's progress handler checks every
  1,000 VM instructions and aborts after roughly ten million instructions, cancellation
  or expiry. The worker busy timeout is 100 ms. These interrupt SQL work; they cannot
  preempt arbitrary OS/native I/O stalls. Reader shutdown never blocks the server thread.
- New optional queries pause at 80% of the writer record or byte limit while storage
  is ready. Existing queries remain bounded. A failed writer with an initialized
  database can still serve readable committed history with explicit failure warnings.

Every page reports its committed ID ceiling, current writer acknowledgement, pending
records/age, reconciliation count, prior unclean-session/incident counts and current
incident reasons. The page boundary is a durable database selection, not a completed
capture barrier, a gap-free interval or a world-save checkpoint. Warnings are global
and conservative; they do not pretend incidents can be localized more precisely.

`STARTUP` marks initialization coverage. Unsupported events, overflow and ambiguous
observations leave visible incidents. Previous unclean sessions warn of possible
missing memory-only work; absence of an exact loss count is not completeness.
Empty results say only that no matching committed rows were found. SQL failure,
timeout, overload, unknown identities and empty results have distinct messages.
Preserve a failed database and consult [STORAGE.md](STORAGE.md); do not delete files
to make a query appear healthy.

## Reload

`reload` reads at most 64 KiB of configuration on a dedicated worker, admits only one
pending reload, validates the whole candidate and applies it on the server thread
after rechecking permission. Only `diagnostics.show-runtime-details` and
`lookup.page-size` can change at runtime. Any storage/reconciliation change requires
restart and rejects the entire candidate; malformed/unknown keys also leave all
active settings unchanged. The file is never overwritten. Existing version-1
configuration files can omit `lookup` and retain the default page size of 10.

See [M2 evidence](TESTING.md#current-m2-evidence) and [measurements](M2_MEASUREMENTS.md)
for actual checks and their limits. There is no performance-superiority claim.
