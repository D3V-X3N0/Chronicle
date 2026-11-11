# Chronicle

Chronicle is an early-stage activity logging and recovery project for Minecraft Paper servers. It aims to help server administrators investigate griefing, inspect block history, and recover builds through targeted rollbacks and restoration.

The goal is a maintainable, performance-conscious alternative to established tools such as CoreProtect. Initial synthetic storage/query measurements exist; no performance advantage over another plugin is claimed.

## Current status

**The development build records qualified player stone/air transitions in SQLite, provides read-only inspection and filtered lookup, and reports durability and coverage status. Recovery is not implemented. It is not comprehensive server protection, and there is no public release.**

M1 and M2 are complete for the qualified scope: historical name/UUID resolution, exact filters, bounded asynchronous readers, requester-owned pagination, permission-aware commands and presentation-only reload. Unit, file-backed database, subprocess crash and packaged-native tests run in the build. Opt-in disposable Paper tests use actual network players, protection plugins, inspection interactions and exact history/command checks after restart. See the [investigation guide](docs/INVESTIGATION.md), [capture qualification](docs/CAPTURE_QUALIFICATION.md), [storage operations](docs/STORAGE.md) and [validation evidence](docs/TESTING.md#current-m2-evidence). The [roadmap](docs/ROADMAP.md) tracks completion gates. The existing [MIT license](LICENSE) is preserved.

## Direction

The stone/air capture, SQLite and investigation paths exist. Expanded event families and recovery below remain planned.

Chronicle prioritises correctness and data integrity, rollback safety, performance, usability, and maintainability, in that order.

- Capture supported changes on the Paper server thread and send immutable records to bounded asynchronous storage workers.
- Start with SQLite, transactional write batches, WAL mode, and explicit reporting of write backlog and history gaps.
- Investigate committed history using player, UTC time, world, radius/origin and action filters, with stable bounded pagination.
- Require a preview and confirmation before rollback; skip conflicting states and record partial progress.
- Restore only changes actually applied by a recorded rollback, with fresh conflict checks.
- Add container, hopper, explosion, environmental, and entity logging in separately tested stages.

The initial release scope is deliberately limited to a tested block logging and recovery workflow. Inventory transaction logging and inventory rollback are different capabilities; neither is implemented, and inventory rollback is outside the first release. See the [product specification](docs/PRODUCT_SPEC.md) for boundaries.

## Intended platform and prerequisites

The build pins **Paper API `26.3.build.248-stable`, Minecraft 26.3, JDK 25 and Gradle 9.9.1**. The smoke harness pins matching Paper server build 248 and its SHA-256. The plugin refuses unqualified runtime builds. Versions were verified against Paper's [artifact repository](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml) and [downloads service](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/248) on 11 November 2026. Packet-driven local tests qualify only the transitions described above; recovery is unavailable.

Contributors need Git and JDK 25; set `JAVA_HOME` to that JDK. The checked-in Gradle Wrapper downloads its checksum-verified distribution. No globally installed Gradle is needed. Dependency downloads require internet on the first build. Folia, Spigot, older Minecraft versions and other forks are outside the initial support scope.

## Building

On Windows PowerShell:

```powershell
.\gradlew.bat clean build
```

On Linux/macOS:

```sh
bash ./gradlew clean build
```

The development plugin is `build/libs/chronicle-0.1.0-SNAPSHOT.jar`. Install only that JAR in a disposable Paper 26.3 build 248 server's `plugins/` directory, restart, and run `chronicle status` from console. SQLite starts on its own worker; capture waits for `READY`. Players need explicit grants for `chronicle.status`, `chronicle.lookup`, `chronicle.inspect` or `chronicle.reload`; all default to false even for operators. See the [investigation workflow](docs/INVESTIGATION.md). Configuration is created at `plugins/Chronicle/config.yml`; invalid configuration disables the plugin and preserves the file. Only runtime-detail display and lookup page size can reload; storage settings require restart. History is stored at `plugins/Chronicle/history.db`; never delete a failed database or its WAL to silence an error.

`build/libs/chronicle-0.1.0-SNAPSHOT-paper-test-plugin.jar` is a separate diagnostic fixture. Do not install it on a production server. `build` runs unit/database/crash/native tests and compiles the harness without starting Minecraft. See [development setup](docs/DEVELOPMENT.md) for the opt-in Paper test command, EULA requirement, exact artifacts and fixture limitations.

## Documentation

| Document | Purpose |
| --- | --- |
| [Product specification](docs/PRODUCT_SPEC.md) | Requirements, command proposals, release scope, and defaults |
| [Architecture](docs/ARCHITECTURE.md) | Capture, storage, threading, rollback safety, and open decisions |
| [Roadmap](docs/ROADMAP.md) | Ordered milestones and evidence required to complete them |
| [Development](docs/DEVELOPMENT.md) | Environment, build tasks, organisation, and conventions |
| [Testing](docs/TESTING.md) | Correctness, failure recovery, performance, and release validation |
| [Investigation](docs/INVESTIGATION.md) | Implemented commands, filters, permissions, inspection and reload |
| [M2 measurements](docs/M2_MEASUREMENTS.md) | Reproducible synthetic index and mixed-load evidence, with limits |
| [Contributing](CONTRIBUTING.md) | How to propose and review useful changes |

## Operational expectations

Chronicle is intended to complement world backups. An asynchronous queue can lose uncommitted records on a crash, and a database transaction cannot atomically commit a Minecraft world save. The design makes those limits visible and requires cautious recovery rather than promising lossless logging or universal undo.

There are no release dates or claims of production adoption. Progress is recorded through implementation, tests and issues. Future contributions should follow the [roadmap dependencies](docs/ROADMAP.md#milestone-3--safe-rollback-and-restoration).
