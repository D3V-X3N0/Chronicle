# Product specification

Status: 13 November 2026. M1/M2 implement qualified stone/air capture, SQLite storage, investigation and permissions. M3 qualifies narrow inert-block recovery on the pinned runtime. Implemented recovery commands/bounds are defined by [RECOVERY.md](RECOVERY.md); expanded capture, retention and broader defaults remain proposals. The published first alpha remains M1/M2 only.

## Users and outcomes

Chronicle serves Paper server administrators and moderators who need to answer who changed a location, what changed, and when; recover a bounded area without overwriting unrelated work; and understand whether the recorded history is complete enough to act on.

The priority order is correctness and data integrity, rollback safety, performance, usability, then maintainability. Speed does not justify silently dropping records, guessing attribution, or replacing a state that fails a safety check.

## Scope and support gates

The owner-authorised `v0.1.0-alpha.1` prerelease exposes completed M1/M2 for disposable evaluation. Its [notes](releases/v0.1.0-alpha.1.md) define the narrower scope. The first **stable** release still requires the recovery and operational gates below; publishing the alpha does not complete M6.

“Supported” means implemented, tested on a named Paper build, documented with limitations, and included in a release compatibility matrix. A listener existing in source is not sufficient.

| Capability | Intended stage | Release boundary |
| --- | --- | --- |
| Direct player block placement and breaking | M1 capture; M2 reliable history | First stable release requirement; exact eligible block/state list established by tests |
| Player UUID attribution, block inspection, filtered lookup | M2 | First stable release requirement |
| Preview, confirmed rollback, restore by operation ID, recovery reporting | M3 | First stable release requirement for eligible block transitions |
| Multi-block structures and block entity metadata | Capture investigation in M1/M2; qualified codecs later | Initially inspection-only or unsupported unless complete grouped capture and restoration pass tests |
| Container and hopper transactions | M4 | Separate logging capability; experimental until each inventory/action combination is qualified |
| Explosions, fluids, fire, growth, decay, pistons, entity block changes | M4, staged by family | Promote separately; incomplete families remain visibly unsupported |
| Inventory/container rollback, player inventory recovery, arbitrary entity resurrection | Later research | Outside first stable release |
| MySQL/MariaDB | Later, after SQLite evidence | No initial driver, schema promise, or supported configuration |

The minimum recovery scope is ordinary, single-block, inert placement/break transitions with complete before/after state and safe surroundings. It excludes inventories, blocks with stored loot-table state, block entities, multi-block structures, fluid-sensitive changes, and dependent physics until explicitly qualified. A block's name alone does not prove a transition safe. The first stable release must be useful for genuine investigation and recovery of this documented subset; it must not be marketed as comprehensive world protection.

Block recovery changes world blocks only. It must not generate drops or experience itself, but it does not reclaim items already mined, dropped or picked up, or refund placement costs. Rebuilding a mined block can therefore increase available materials. Preview and operator documentation must explain that inventory/economy limitation; container contents and stored loot-table states remain excluded to avoid implying item-conserving recovery.

## Functional requirements

| ID | Requirement and observable acceptance condition |
| --- | --- |
| CAP-1 | Store supported successful changes with stable world identity, coordinates, ordered event identity, UTC time, action, actor/cause, and versioned before/after snapshots. Cancelled or denied actions produce no successful-change record. |
| CAP-2 | Store a player UUID when attribution is direct. Distinguish entity, environment, system, and unknown causes. Never assign responsibility solely from proximity or the last player to touch a block. |
| CAP-3 | Preserve distinct repeated changes at one location. Related multi-target changes share a group ID; unsupported or ambiguous snapshots are labelled and excluded from recovery. |
| STO-1 | A successful enqueue means buffered, not durable. Status distinguishes accepted, committed, pending, rejected, and uncertain records; a persistent failure is visible. |
| STO-2 | Transactions are all-or-nothing, retries cannot duplicate events, schema changes are versioned, and startup refuses an unsupported/newer schema without altering it. |
| STO-3 | Queue and payload limits bound memory. Overflow records a coverage incident, alerts staff, and disables unsafe mutations. Existing committed history remains queryable with warnings. |
| QRY-1 | Inspection shows recent committed changes at the selected block, actor/cause, time, old/new state, operation links, and uncertainty. Inventory contents require a separate permission. |
| QRY-2 | Lookup supports player UUID/name resolution, UTC time range, world, radius/origin, and action. Results are ordered, paginated, bounded, and distinguish empty results from unavailable or incomplete history. |
| RB-1 | Rollback always starts with a preview tied to an immutable selection and requester. Confirmation rechecks permissions, history health, state, and plan validity. No implicit global operation. |
| RB-2 | Apply inverse changes in reverse capture order, preserve unselected later changes, and skip whole dependent groups on conflict. Unsupported state is never approximated as a simpler block. |
| RB-3 | Persist operation intent before mutation and track each step. Pause on storage failure or uncertainty; report applied, conflicted, unsupported, pending, and failed counts separately. |
| RB-4 | Restore takes a rollback operation ID and reverses only its successfully applied, not-yet-restored steps. It uses fresh previews and conflicts; it never reruns the original broad filter. |
| OPS-1 | Status exposes storage readiness, effective configuration, coverage boundaries, queue pressure, durability position, schema/runtime versions, and operation state. |
| OPS-2 | Reload validates a complete candidate configuration before atomically applying permitted settings; rejected changes leave the previous settings active. Storage, codecs, and queue sizing require restart. |
| OPS-3 | Retention is opt-in, bounded, previewable, and preserves records needed by active operations and retained restoration rights. Expiry is visible before deletion. |

## Proposed administrator workflow

Investigation commands are documented in the [investigation guide](INVESTIGATION.md). The rollback, restore and operation-status examples below are implemented for the narrower [M3 scope](RECOVERY.md). Grammar usability has been tested through scripted fixtures, not a field administrator study.

```text
/chronicle inspect
/chronicle lookup player:Alex time:2h radius:20 world:world action:block-break
/chronicle lookup page:<cursor>
/chronicle rollback preview player:<uuid> time:2h radius:20 world:world action:block-break
/chronicle rollback confirm <preview-id>
/chronicle restore preview <rollback-id>
/chronicle restore confirm <preview-id>
/chronicle status
/chronicle status operation:<operation-id>
/chronicle reload
```

Inspection toggles a read-only investigation mode for a player. On the tested interactions, it must prevent that inspector's click from modifying the selected block or opening a container; observe cancellation and interaction-result semantics without bypassing protection plugins. It must not grant build access. Clear the mode on logout and plugin disable.

Filters combine with AND; a documented comma-separated list within one filter uses OR. `time:2h` resolves once to an absolute UTC interval ending at request time. Advanced explicit timestamps use ISO 8601 UTC and half-open intervals `[from, to)`. Preview and output show the resolved interval, rather than a moving “last two hours”. Player names resolve against stored UUID identities, with ambiguity errors; no network lookup is required on the server thread.

A radius is an inclusive horizontal circle in block coordinates, with all world heights included. Freeze the player's block X/Z as its centre when the request is submitted; show that centre and the world UUID in the preview. Negative chunk coordinates use floor division. Console requests require `world:<name-or-uuid>` and `origin:<x>,<z>` when using a radius. Reject ambiguous world names and unknown UUIDs. An explicit time range, world, bounded area, and action set are required for mutations; an omitted player means all actors and must be highlighted. No wildcard all-world rollback in the first release.

Lookup returns committed history and displays write lag and coverage warnings. Pagination uses an opaque cursor bound to the requester, filters, fixed history boundary, and expiry; new records must not shift earlier pages. Do not hold a database transaction open while waiting for a user to turn a page. If pruning invalidates a result set, require a new query.

Preview shows scope, eligible changes/groups, excluded and conflicting counts with reasons, known history gaps, and an expiring confirmation ID. Missing or unsupported history cannot be treated as “nothing happened”. A preview is not a reservation of the world: confirmation and every execution step revalidate it. Progress messages include the operation ID and explain partial completion. Reusing confirmation must return the same operation, never start another.

Example errors: “History is 8 seconds behind; rollback is paused until storage catches up”, “Block changed after preview; group skipped”, “State codec is unsupported on this server version”, and “Operation needs reconciliation after restart; nothing has resumed automatically”. Values in examples are illustrative, not targets.

## Proposed permissions and usability

All Chronicle permission nodes default to `false` for players, including operators; grant explicitly through the server's permission system. Local console access is trusted but still obeys scope, preview, and conflict requirements. Recheck permission at execution and before delivering asynchronous results.

| Node | Access |
| --- | --- |
| `chronicle.inspect` | Toggle block inspection |
| `chronicle.lookup` | Read permitted history |
| `chronicle.lookup.inventory` | View potentially sensitive item metadata in qualified inventory history |
| `chronicle.rollback` | Preview and confirm rollback |
| `chronicle.restore` | Preview and confirm restoration |
| `chronicle.status` | View health and operation progress |
| `chronicle.reload` | Reload permitted configuration |
| `chronicle.alerts` | Receive rate-limited operational warnings |

M2 completion suggests permitted subcommands, action identifiers, at most 32 loaded worlds, a bounded recent actor-name cache and the requester's current cursor. Future recovery completion may add active operation IDs. It must not trigger unbounded database scans or expose inventory text to unauthorised users. Keep permission checks separate from filter parsing. A future world-specific access policy needs its own specification; it is not implied here.

Use concise Adventure messages with plain-text fallbacks for console, readable timestamps, clickable next-page/preview actions where appropriate, and a reason plus remedy for errors. Never insert untrusted names, item text, or database errors as executable commands or unescaped formatting.

## Initial configuration proposals

These are starting values for development, not measured capacity or latency promises. M5 must justify or change numeric defaults before stable release; every hard limit has an explicit error path.

| Setting | Proposed default and rationale |
| --- | --- |
| Storage | Local SQLite, WAL, `synchronous=FULL`; prefer durability of committed batches |
| Capture | Direct player block placement/breaking in all loaded worlds; advanced families off until qualified |
| Queue | At most 10,000 change records and 32 MiB of accounted payload, whichever comes first; bounds event count and large snapshots |
| Snapshot/group admission | At most 256 KiB per record; groups must fit as a whole; reject visibly instead of truncating |
| Write batch | At most 500 records or 1 MiB, with a 100 ms flush interval; whichever triggers first, subject to whole-group admission |
| Readers | Two concurrent read tasks and 32 waiting queries; refuse extra work visibly |
| SQLite busy timeout | 1 second on workers only, with bounded retry policy; no server-thread wait |
| Shutdown drain | 10 seconds; then report unfinished work and leave the session unclean |
| Lookup | 10 rows per page, 2-minute cursor expiry; interactive readability and bounded retained context |
| Mutation scope | Radius at most 100 blocks, at most 10,000 candidate changes, preview expires after 60 seconds; cap work and stale plans |
| Mutation scheduling | One active operation, at most 50 steps or 2 ms of measured work per tick, whichever is reached first; yields most of a nominal 50 ms tick |
| Chunk handling | Loaded chunks only initially; never generate missing chunks for recovery |
| Retention | Automatic pruning off; disk growth alerts on; explicit owner choice required before deletion |
| Overload | Continue normal gameplay, latch degraded coverage, pause Chronicle mutations, alert staff; no automatic server shutdown |

An oversized group may exceed the normal batch target only within the hard group/queue admission cap and must use a bounded standalone transaction; it cannot be split into misleading partial history. A single world update can exceed the tick budget; the scheduler cannot preempt a Paper API call. Qualification and diagnostics must account for that limitation.

## Non-functional requirements and exclusions

No filesystem/database/network I/O or blocking future waits in capture or world-apply callbacks. All queues, caches, query results, and operation plans have limits. Historical IDs and schema versions remain stable; unknown codecs fail safely. Diagnostics must expose coverage loss even when gameplay continues. See [testing](TESTING.md) for evidence gates and [M2 measurements](M2_MEASUREMENTS.md) for the limited synthetic baseline; no production capacity or superiority is claimed.

Chronicle is not a backup system, anticheat, permissions/claims plugin, or replacement for server access controls. Chat, commands, IP addresses, private messages, and unrelated player telemetry are outside scope. Do not add telemetry collection by default. Staff must control access to history, item metadata, backups, and the plugin directory.

Initial exclusions also include Folia, cross-server logging, remote databases, a web UI, an external public API, CoreProtect database import or command compatibility, arbitrary plugin/NMS changes without events, and universal Minecraft-version migration. Direct plugin writes may leave no capturable event; absence of a row is never proof that a player or plugin made no change.

Potential CC CLI Bring Your Own Project submission does not change feature priorities or release gates. Only real repository history, reproducible work, and demonstrated behaviour may be represented as completed.
