# Chronicle

Chronicle is an early-stage activity logging and recovery project for Minecraft Paper servers. It aims to help server administrators investigate griefing, inspect block history, and recover builds through targeted rollbacks and restoration.

The goal is a maintainable, performance-conscious alternative to established tools such as CoreProtect. Chronicle has no benchmark results or demonstrated performance advantage yet.

## Current status

**The first development build is available from source. It loads a plugin and reports its status, but does not capture, persist, inspect or recover world history. Do not rely on Chronicle to protect a server. There is no public release.**

Implemented: a pinned Gradle build, validated configuration, `/chronicle status` with explicit permission checks, unit/packaging tests, CI configuration, and an opt-in disposable Paper smoke-test harness. A separate test-plugin JAR observes event attempts; it is not production logging. The [roadmap](docs/ROADMAP.md) tracks remaining work. The existing [MIT license](LICENSE) is preserved.

## Direction

The logging and recovery features in this section remain planned.

Chronicle prioritises correctness and data integrity, rollback safety, performance, usability, and maintainability, in that order.

- Capture supported changes on the Paper server thread and send immutable records to bounded asynchronous storage workers.
- Start with SQLite, transactional write batches, WAL mode, and explicit reporting of write backlog and history gaps.
- Provide player, time, world, radius, and action filters for investigation.
- Require a preview and confirmation before rollback; skip conflicting states and record partial progress.
- Restore only changes actually applied by a recorded rollback, with fresh conflict checks.
- Add container, hopper, explosion, environmental, and entity logging in separately tested stages.

The initial release scope is deliberately limited to a tested block logging and recovery workflow. Inventory transaction logging and inventory rollback are different capabilities; neither is implemented, and inventory rollback is outside the first release. See the [product specification](docs/PRODUCT_SPEC.md) for boundaries.

## Intended platform and prerequisites

The build pins **Paper API `26.3.build.248-stable`, Minecraft 26.3, JDK 25 and Gradle 9.9.1**. The smoke harness pins matching Paper server build 248 and its SHA-256. Versions were verified against Paper's [artifact repository](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml) and [downloads service](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/248) on 11 November 2026. Compilation does not qualify logging or recovery support.

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

The development plugin is `build/libs/chronicle-0.1.0-SNAPSHOT.jar`. Install only that JAR in a disposable Paper server's `plugins/` directory, restart, and run `chronicle status` from console. Players need an explicit `chronicle.status` grant; it defaults to false even for operators. Configuration is created at `plugins/Chronicle/config.yml`; invalid configuration disables the plugin and preserves the file. Changes currently require restart.

`build/libs/chronicle-0.1.0-SNAPSHOT-paper-test-plugin.jar` is a separate diagnostic fixture. Do not install it on a production server. `build` runs tests and compiles the harness without starting Minecraft. See [development setup](docs/DEVELOPMENT.md) for the opt-in Paper test command, EULA requirement, exact artifacts and fixture limitations.

## Documentation

| Document | Purpose |
| --- | --- |
| [Product specification](docs/PRODUCT_SPEC.md) | Requirements, command proposals, release scope, and defaults |
| [Architecture](docs/ARCHITECTURE.md) | Capture, storage, threading, rollback safety, and open decisions |
| [Roadmap](docs/ROADMAP.md) | Ordered milestones and evidence required to complete them |
| [Development](docs/DEVELOPMENT.md) | Environment, build proposal, organisation, and conventions |
| [Testing](docs/TESTING.md) | Correctness, failure recovery, performance, and release validation |
| [Contributing](CONTRIBUTING.md) | How to propose and review useful changes |

## Operational expectations

Chronicle is intended to complement world backups. An asynchronous queue can lose uncommitted records on a crash, and a database transaction cannot atomically commit a Minecraft world save. The design makes those limits visible and requires cautious recovery rather than promising lossless logging or universal undo.

There are no release dates or claims of production adoption. Progress will be recorded through real implementation, tests, issues, and releases. Contributions should start with the [first actionable development task](docs/ROADMAP.md#first-development-task).
