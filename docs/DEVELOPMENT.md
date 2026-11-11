# Development guide

Status: proposed workflow, 11 November 2026. This repository currently contains documentation, issue templates and the existing MIT license. There are no source files, build scripts, Wrapper, CI jobs or tests. Commands marked “planned” will not work until the indicated milestone adds them.

The [repository evidence and decisions](ARCHITECTURE.md#repository-evidence-and-decisions) describe the initial Git history and existing MIT license. Documentation review can be performed today; plugin build and runtime verification cannot. Do not present this plan as completed engineering or add invented releases, users or benchmark badges.

## Proposed environment and dependencies

| Tool / dependency | Direction and version policy |
| --- | --- |
| JDK | Java 25 for compilation and the initial Paper runtime; avoid preview language features |
| Minecraft / Paper | Target 26.3 initially; pin a verified stable API artifact and server build/checksum in M1 |
| Gradle | Kotlin DSL, checked-in Wrapper pinned to a Java 25-compatible stable release; no global Gradle needed after bootstrap |
| SQLite | Xerial `org.xerial:sqlite-jdbc`, pinned; verify the bundled engine is at least 3.51.3 and maintained |
| Tests | JUnit Jupiter through a pinned JUnit BOM; real file-backed SQLite integration tests; real Paper fixtures |
| Player-facing text | Paper-provided Adventure; do not bundle another copy unnecessarily |

Paper's [setup guide](https://docs.papermc.io/paper/dev/project-setup/) documents `compileOnly` Paper API dependencies and Java 25. Its [Java requirements](https://docs.papermc.io/paper/getting-started/) require Java 25 for 26.1+. [Gradle's compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html) supports running on Java 25 from 9.1.0 onward; select and pin an appropriate maintained version in M1, not a dynamic “latest”. These sources were checked on 11 November 2026.

The [Xerial driver](https://github.com/xerial/sqlite-jdbc) bundles native libraries. Package it deliberately, retain service descriptors/native resources and license notices, and test extraction/loading on supported hosts. Avoid blindly relocating JDBC native resource paths. An initial self-contained distributable is preferred to runtime dependency downloads, but validate the packaging plugin and artifact contents in M1. Do not assume Paper's internal libraries form Chronicle's dependency contract.

[JUnit](https://junit.org/) currently identifies JUnit 6 as its current generation. Use Jupiter APIs with a compatible pinned release; no test dependency has been installed or selected in this checkout. A Paper mocking library is optional only if it supports the target API and adds useful tests. It does not replace a real server.

Use plain JDBC prepared statements and small repositories; no connection pool is needed for one owned SQLite writer and two fixed readers. A future MySQL backend may justify a maintained pool such as HikariCP, but it is not an initial dependency. Numbered transactional SQL migrations are sufficient initially; reconsider an established migration library if migration requirements become complex.

## Build bootstrap and proposed workflow

The [first roadmap task](ROADMAP.md#first-development-task) owns creation of the build. It must add:

- `settings.gradle.kts`, `build.gradle.kts`, a verified Gradle Wrapper and distribution checksum, and exact dependency versions with verification/locking where applicable.
- A Java 25 toolchain, `compileOnly` Paper API from its official repository, packaged runtime JDBC dependency, JUnit execution, and separated integration-test tasks.
- `src/main/resources/plugin.yml` with the real entry point and target `api-version`, defaults in `config.yml`, and versioned SQL migration resources as storage is added.
- Minimal CI for compilation/tests and future packaging, plus ignored local server/build output. Do not commit downloaded server JARs, world data, databases, credentials or logs.

The 26.3 API uses the `io.papermc.paper:paper-api` artifact and build-specific version format. Resolve an actual stable coordinate, rather than copying the dynamic `26.3.build.+` example into a reproducible build. The exact coordinate is intentionally not fabricated here.

Proposed task contract (not currently available):

| Command, using the future Wrapper | Purpose / introduction |
| --- | --- |
| `./gradlew clean build` | Compile, run standard checks and produce the distributable; M1 |
| `./gradlew test` | Pure logic and small adapter unit tests; M1 |
| `./gradlew integrationTest` | File-backed database and worker/lifecycle tests; M1 |
| `./gradlew paperIntegrationTest` | Explicit disposable Paper fixture suite; begin M1, expand M2/M3 |
| `./gradlew runServer` | Optional local disposable server task, if configured in M1 |
| `./gradlew benchmark` | Reproducible synthetic/DB benchmark entry point; M5 |

Windows equivalents use `./gradlew.bat`. `build` should include unit and database integration checks once both exist; the slower server suite can remain explicit and separately required in CI. Record actual task behaviour when implemented. Avoid publishing a build command as proof of success without running it.

For a future local runtime test, use a separate directory outside production server data, obtain the pinned Paper JAR/checksum from PaperMC, satisfy the server's EULA requirements, and run with the documented JDK. Start with only Chronicle and the fixture plugin, then add selected interaction plugins. Keep a resettable world fixture and recorded server settings. Never point the fixture task at an existing production world or database. The harness must not silently accept terms or upload logs/worlds.

## Proposed organisation

This tree shows future files, not existing content:

```text
build.gradle.kts / settings.gradle.kts / gradlew / gradlew.bat
gradle/wrapper/
src/main/java/<project-namespace>/
  bootstrap/       config/          model/
  capture/         platform/paper/
  storage/sqlite/  query/           rollback/
  command/         diagnostics/
src/main/resources/
  plugin.yml       config.yml      db/migration/
src/test/java/
src/integrationTest/java/
src/paperIntegrationTest/           # harness/fixtures, exact layout chosen in M1
docs/                              # existing design documents
.github/workflows/                 # future build and release automation
```

Use a valid lowercase Java package derived from project ownership; the GitHub handle's hyphens cannot appear directly in a Java identifier. Keep one distributable/project until code and test constraints demonstrate a reason to split it. Package boundaries follow [architecture responsibilities](ARCHITECTURE.md#responsibilities-and-data-flow).

## Engineering conventions

- Keep domain models immutable and Paper-free. Prefer descriptive names, small methods and explicit constructor dependencies; avoid a service locator or global mutable plugin instance.
- State thread ownership on adapters and asynchronous boundaries. Futures transport immutable results; no world API work on storage workers and no blocking database wait in a tick callback.
- Use stable UUIDs and namespaced identifiers. Store UTC timestamps for filters, ordered capture identities for causality, and an injected clock in pure logic tests.
- Persist complete versioned snapshots or explicitly mark unsupported/partial evidence. Do not catch a codec exception and silently substitute air, empty inventory or a generic material.
- Bind all query values, allowlist SQL fragments such as sort columns/actions, and bound parsed time/radius/result limits. Validate before allocating or scheduling expensive work.
- Treat cancellation, no-op, denied access, overload and partial failure as ordinary explicit outcomes. Error messages include an operation/correlation ID and next action, without leaking full item data or sensitive paths by default.
- Append migrations; never edit one that has been applied to a released database. Document snapshot/schema compatibility and exercise upgrade failures alongside changes.
- Start with consistent four-space Java indentation, UTF-8 and LF endings. Configure a standard formatter/checker during build bootstrap rather than maintaining handwritten style policing.
- Write meaningful tests for invariants and past defects. Use an independent reference model for rollback; do not validate an algorithm by calling the same algorithm to generate expectations.
- Require focused changes and evidence. Update affected requirements, support matrix and user guidance when behaviour changes. Avoid optimisation or dependencies without a concrete need.

## Review and reproducibility

Before a PR, read [CONTRIBUTING.md](../CONTRIBUTING.md), run the checks available for that stage, and state any checks that could not run. For documentation-only changes, validate links, examples, factual status and `git diff --check`; don't invent a passing build.

Future CI should pin action revisions and dependency inputs, run with minimal permissions, avoid production secrets/data, and keep untrusted PR code isolated from release credentials. Store test seeds, runtime versions, fixtures and failure reports so bugs can be reproduced. Release jobs must exercise the packaged JAR, not just the IDE classpath.

Architecture, capture eligibility and recovery changes need review against the failure cases in [TESTING.md](TESTING.md). A release claim must point to actual implementation/test evidence. Any eventual programme submission should describe this real history and work without additional artificial repository activity.
