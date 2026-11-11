# Chronicle

Chronicle is a planned activity logging and recovery plugin for Minecraft Paper servers. It aims to help server administrators investigate griefing, inspect block history, and recover builds through targeted rollbacks and restoration.

The goal is a maintainable, performance-conscious alternative to established tools such as CoreProtect. Chronicle has no benchmark results or demonstrated performance advantage yet.

## Current status

**Planning and documentation only. There is no runnable plugin, build system, test suite, or release in this checkout. Do not rely on Chronicle to protect a server yet.**

The repository review on 11 November 2026 found one initial commit, `611d983`, containing a short README and an MIT license. This foundation adds design and contribution documentation; it does not implement the plugin. All features and commands below are planned. The existing [MIT license](LICENSE) is preserved.

## Direction

Chronicle prioritises correctness and data integrity, rollback safety, performance, usability, and maintainability, in that order.

- Capture supported changes on the Paper server thread and send immutable records to bounded asynchronous storage workers.
- Start with SQLite, transactional write batches, WAL mode, and explicit reporting of write backlog and history gaps.
- Provide player, time, world, radius, and action filters for investigation.
- Require a preview and confirmation before rollback; skip conflicting states and record partial progress.
- Restore only changes actually applied by a recorded rollback, with fresh conflict checks.
- Add container, hopper, explosion, environmental, and entity logging in separately tested stages.

The initial release scope is deliberately limited to a tested block logging and recovery workflow. Inventory transaction logging and inventory rollback are different capabilities; neither is implemented, and inventory rollback is outside the first release. See the [product specification](docs/PRODUCT_SPEC.md) for boundaries.

## Intended platform and prerequisites

The proposed development baseline is **Paper for Minecraft 26.3 and JDK 25**. Paper's current [download page](https://papermc.io/downloads/paper) lists 26.3, and its [project setup guide](https://docs.papermc.io/paper/dev/project-setup/) uses the 26.3 API and a Java 25 toolchain. Sources were checked on 11 November 2026. An exact API artifact and matching server build must be pinned during the first milestone; this is a design target, not a compatibility claim.

Future contributors will need Git, JDK 25, a Java IDE or editor, and a disposable Paper test server. The planned build uses a checked-in Gradle Wrapper and Kotlin build scripts. Folia, Spigot, older Minecraft versions, and other forks are outside the initial support scope.

## Building

There is currently nothing to compile. No Gradle Wrapper or build files exist yet.

Once Milestone 1 supplies and validates the build, the intended commands are:

```sh
# Planned workflow; not runnable in the current checkout.
./gradlew clean build
```

On Windows, the equivalent will be `./gradlew.bat clean build`. The expected distributable location is `build/libs/`; the actual artifact name and installation procedure will be documented when packaging exists. See [development setup](docs/DEVELOPMENT.md) for the bootstrap task and planned checks.

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
