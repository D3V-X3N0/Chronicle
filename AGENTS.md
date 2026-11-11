# Chronicle repository guidance

These instructions apply throughout the repository. Keep shared agent guidance here; `CLAUDE.md` points to this file. Follow the user's current task and preserve unrelated work.

## Project and current phase

Chronicle is a planned Minecraft Paper activity logging and recovery plugin. Priorities, in order: correctness and data integrity, rollback safety, performance, usability, maintainability.

M1 includes qualified direct-player stone/air capture, bounded ordered reconciliation, SQLite lifecycle/schema, atomic idempotent writing and coverage/status diagnostics. Completed M2 adds schema-2 identity aliases, bounded asynchronous investigation, protected inspection, stable cursors and presentation-only reload. Disposable packet/restart tests and synthetic mixed-load measurements cover these contracts; see `docs/INVESTIGATION.md`, `docs/M2_MEASUREMENTS.md` and current test evidence. Recovery is not implemented, and there is no release. Inspect the checkout and evidence before relying on this status; update it as implementation changes.

- For documentation tasks, keep changes to documentation; do not bootstrap the plugin or install dependencies.
- When the user requests implementation, work on the requested scope using the roadmap's dependencies and acceptance criteria. The planning status is not a permanent prohibition on implementation.
- Distinguish implemented behaviour, selected design direction, proposals and future work. Never invent passing tests, compatibility, benchmarks, adoption or development history.
- Preserve the existing MIT [LICENSE](LICENSE) and attribution. Licensing changes require an explicit owner decision.

## Read the relevant project documents

Start with [README.md](README.md), inspect Git status, and read the documents relevant to the change:

| Document | Use it for |
| --- | --- |
| [PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) | Requirements, feature boundaries, commands, permissions and proposed defaults |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Capture, threading, persistence, recovery invariants and open decisions |
| [ROADMAP.md](docs/ROADMAP.md) | Milestone dependencies, the first development task and completion evidence |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Environment, build tasks, package boundaries and engineering conventions |
| [TESTING.md](docs/TESTING.md) | Test layers, failure scenarios, independent correctness checks and release gates |
| [INVESTIGATION.md](docs/INVESTIGATION.md) | Implemented query, permission, inspection and reload contracts |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contribution scope, review expectations and licensing |

Treat design documents as plans until implementation and tests establish the behaviour. Resolve inconsistencies explicitly and update the affected documents together; do not silently broaden scope.

## Technical direction

The pinned baseline is Paper API `26.3.build.248-stable`, matching Minecraft 26.3 server build 248, Java 25, Gradle 9.9.1 and JUnit 6.1.3. It is one Gradle Kotlin DSL project using public Paper APIs and conventional `plugin.yml`. Dependency locks/checksums and the server pin are checked in. Consult authoritative sources when changing versions; compiling against a target does not qualify capture/recovery compatibility.

Start with SQLite through JDBC, one writer, bounded readers, WAL and `synchronous=FULL`. Keep domain models immutable and independent of Paper. Use small explicit dependencies and concrete repositories; avoid premature multi-module splits, backend frameworks, custom concurrency primitives or optimisation without measurements.

Folia, remote databases and inventory restoration are outside the first release. Expanded logging is staged by event family. Logging a change does not establish that it can be safely reversed.

## Correctness rules for future implementation

- Access world, block, inventory, entity and player APIs on the owning Paper server thread. Pass immutable copied data to workers; no database/file/network I/O or blocking database waits in capture or world-apply callbacks.
- Honour cancellation and event-specific denial. Monitoring callbacks are not universal post-commit hooks. Preserve repeated changes and group related changes without duplicate records.
- Record stable UUIDs, ordered capture identities and complete versioned snapshots. Label unknown attribution or incomplete state; never substitute air, empty inventories or guessed player responsibility.
- Bound queues, payloads, reconciliation buffers, queries and plans. Enqueue is not durable commit. Surface rejected work, uncertainty and coverage gaps; never silently evict accepted history.
- Keep batch insertion atomic and retries idempotent. Use bound SQL parameters, forward versioned migrations and safe backup/reopen checks; never replace a failed database with an empty one.
- Require frozen previews, confirmation and fresh conflict checks for rollback. Preserve unselected edits, apply eligible inverses in reverse capture order, and journal intent before world mutation.
- Restore only verified steps actually applied by the referenced rollback. Keep original history and distinguish applied, skipped, failed and uncertain outcomes.
- Treat database commit and world-save durability separately. A clean plugin shutdown is not a world-save checkpoint; reconcile interrupted operations and revalidate retained effects after restart before reuse.
- Budget world work by elapsed time and step count. Do not suppress unrelated history, generate drops/XP during recovery, or claim block rollback conserves previously mined items.

The architecture contains the full protocols and their limitations. Narrow an unproven capability to a safe tested subset instead of weakening these invariants to satisfy a feature checklist.

## Validation

For documentation changes:

- Run `git diff --check`; also check newly created files, which an unstaged Git diff does not include.
- Verify local Markdown links/anchors, examples and status claims. Keep UTF-8 text, final newlines and consistent formatting.
- Report documentation checks as such; do not substitute them for code tests.

For code changes, run `.\gradlew.bat clean build` on Windows or `bash ./gradlew clean build` on Linux/macOS with JDK 25. This runs JUnit/packaging checks and compiles the separate fixture/harness. `integrationTest` runs file-backed SQLite, packaged native-loading and subprocess crash tests as part of `build`. `paperIntegrationTest` and `paperPacketTest` start disposable Paper servers only with explicit EULA acceptance via `-PacceptMinecraftEula=true`; preserve that opt-in. Synthetic dispatch fixtures are not real-player capture qualification; `paperPacketTest` uses actual loopback player packets and checks durable history after restart. See `docs/CAPTURE_QUALIFICATION.md` and `docs/STORAGE.md`. Run relevant checks, add meaningful regression tests for fixes, and report unavailable checks. Use independent expected states for future rollback tests.

Use disposable worlds/databases for runtime and failure tests. Never test destructive recovery against production data or include secrets, private player data, downloaded servers, generated binaries or local databases in changes.

`historyLoadTest` is an opt-in synthetic index/mixed-load task (default 10,000/100,000/1,000,000 starting rows); it creates only disposable databases under `build/load-tests/`. It is not a Paper tick-performance benchmark. Preserve needed measurements/logs before `clean`, which deletes generated evidence.

## Change discipline and handoff

Keep changes focused and preserve user edits. Do not reset, discard or overwrite unrelated work. Make routine implementation choices within the authorised task; raise material uncertainty with evidence and a concrete recommendation.

The owner has requested ongoing Git/GitHub maintenance for this project. Keep relevant issues and milestones aligned with actual work, commit completed changes with descriptive messages, and synchronise the active branch after appropriate checks unless the current request says otherwise. Check for existing issues before creating focused follow-ups with dependencies and testable acceptance criteria. Link related issues from commits/PRs, and close them only when their criteria are satisfied. Never manufacture activity or mark unverified work complete.

Preserve unrelated changes, use normal pushes without rewriting shared history, and report any failed checks or synchronisation. Publishing a release remains a separate owner-authorised action.

At completion, report what changed, validation performed, material limitations and any remaining decision. Mark roadmap work complete and advertise support only when its stated evidence exists. Keep this file current as the repository gains real build and test workflows.
