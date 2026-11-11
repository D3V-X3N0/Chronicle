# Development guide

Status: first build implemented, 11 November 2026. The project builds a bootstrap plugin, validates configuration and provides `/chronicle status`. Persistent event capture, SQLite, queries and rollback are not implemented. [ROADMAP.md](ROADMAP.md) separates the bootstrap from the remaining foundation work.

## Environment and pinned inputs

| Input | Current pin / policy |
| --- | --- |
| Java | JDK 25 toolchain and Java 25 bytecode; no preview features |
| Paper API | `io.papermc.paper:paper-api:26.3.build.248-stable`, compile-only in the distributable |
| Paper test server | Minecraft 26.3, build 248; URL and SHA-256 in `gradle/paper-test.properties` |
| Gradle | 9.9.1, official Wrapper scripts/JAR and verified distribution checksum |
| JUnit | Jupiter via BOM 6.1.3 |
| SQLite JDBC | Not yet added; remains a later M1 task |

Set `JAVA_HOME` to an installed JDK 25. The build does not automatically install a JDK. The Wrapper retrieves Gradle; no global Gradle is required. An IDE should import this as a Gradle project using JDK 25. Initial dependency resolution needs internet; cached builds can use `--offline`.

Pins were checked against [Paper's Maven metadata](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml), [Paper's server build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/248), [Gradle's version service](https://services.gradle.org/versions/current), and [JUnit's Maven metadata](https://repo.maven.apache.org/maven2/org/junit/junit-bom/maven-metadata.xml). Paper's [setup guide](https://docs.papermc.io/paper/dev/project-setup/) and Gradle's [compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html) explain the platform/toolchain requirements. Recheck authoritative sources when upgrading.

## Build and checks

Use `.\gradlew.bat` in Windows PowerShell or `bash ./gradlew` on Linux/macOS before the task names below. `bash` also works when the checkout does not preserve executable permissions.

| Task | Implemented behaviour |
| --- | --- |
| `clean build` | Compile with lint warnings treated as errors, run JUnit and packaged-JAR checks, compile the harness, produce both JARs |
| `test` | Configuration, command/permission, artifact and observation-buffer tests; no Minecraft process |
| `paperTestPluginJar` | Build the separate `ChronicleFixture` diagnostic plugin |
| `paperIntegrationTest` | Explicit opt-in real Paper startup/lifecycle and synthetic-dispatch tests; EULA acceptance required |

There is no `integrationTest` database task, `runServer` task or `benchmark` task yet. Add them when the relevant implementation exists rather than presenting empty tasks as checks.

Artifacts:

- `build/libs/chronicle-0.1.0-SNAPSHOT.jar`: the main bootstrap plugin, containing Chronicle classes, descriptor, defaults and MIT license. Paper/Adventure are provided by the server; no third-party runtime library is bundled.
- `build/libs/chronicle-0.1.0-SNAPSHOT-paper-test-plugin.jar`: the separate disposable-server fixture. It is not a Chronicle release artifact.
- `build/reports/tests/test/index.html`: local JUnit report. XML reports are under `build/test-results/test/`.

Gradle archives have stable entry ordering and timestamps disabled. The build checks that the actual plugin JAR has Java 25 bytecode, an expanded version, the correct descriptor and license, and no server or fixture classes.

`gradle.lockfile` locks resolved dependency versions; `gradle/verification-metadata.xml` checks artifacts and metadata by SHA-256. The initial checksums record downloads from the declared official repositories, not independently signed provenance. Review upstream changes before intentionally regenerating them with `clean build --write-locks --write-verification-metadata sha256`. Do not regenerate checksums merely to suppress an unexplained mismatch.

The Wrapper JAR's verified SHA-256 is `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`. Its embedded `META-INF/LICENSE` and the scripts' upstream notices are retained. Distribution verification is configured in `gradle/wrapper/gradle-wrapper.properties`.

## Disposable Paper tests

The harness is [PaperSmokeTest.java](../src/paperIntegration/java/io/github/d3vx3n0/chronicle/harness/PaperSmokeTest.java). Review and accept the [Minecraft EULA](https://www.minecraft.net/eula) before opting in. The harness must not silently accept terms or upload logs/worlds.

After acceptance, run:

```powershell
# Windows PowerShell
.\gradlew.bat paperIntegrationTest -PacceptMinecraftEula=true
```

```sh
# Linux/macOS
bash ./gradlew paperIntegrationTest -PacceptMinecraftEula=true
```

Without the property, the task fails before any server download or server-directory creation. With it, the harness verifies the pinned Paper JAR, creates fresh directories under `build/paper-tests/`, binds to `127.0.0.1` on an ephemeral port, disables RCON/query, and starts Java with at most 1 GiB heap. Paper may download its own runtime inputs on first startup. It never uses an existing world.

The first run loads both packaged plugins, checks status/permission behaviour, exercises controlled event dispatch and block sampling, then stops. The second starts the main plugin with an unsupported configuration version and verifies refusal, file preservation and server shutdown. Each run has a four-minute deadline and child-process cleanup. It retains `console.log` and the disposable world for diagnosis; `clean` removes build output, including this evidence.

These fixtures use **synthetic player events dispatched through a real Paper runtime** and controlled world mutations. They validate listener routing, cancellation flags and sample ordering. They do not prove vanilla packet-driven event timing, real player attribution, protection-plugin interoperability or production capture completeness.

For manual qualification, use a separately created disposable Paper 26.3/build 248 server and install both JARs. Do not set `chronicle.fixture.autorun`; the fixture otherwise starts its synthetic tests and shuts down the server. Grant the tester `chronicle.fixture` explicitly. Perform ordinary stone placement/breaking in survival and creative, repeat same-location changes, and inspect the latest ten attempts with `/chroniclefixture`. Repeat with a protection plugin that cancels actions and a fixture that denies placement. Record actual before/after world states and exact settings. The buffer holds at most 256 attempts, reports rejected attempts, skips unsupported multi-place events and never loads chunks to sample them. Restart to clear it.

The observation labels deliberately include cancelled attempts and `LATER_SAMPLE_ONLY`: a later sample cannot establish every intermediate event state. Do not promote these records into production history without completing the capture-contract investigation and independent real-player tests in [TESTING.md](TESTING.md).

## Repository organisation

```text
build.gradle.kts / settings.gradle.kts / gradlew / gradlew.bat
gradle/wrapper/                      # official Wrapper and distribution pin
gradle/paper-test.properties         # server URL and checksum
gradle/verification-metadata.xml     # dependency checksums
gradle.lockfile
src/main/java/io/github/d3vx3n0/chronicle/
  bootstrap/                        # plugin lifecycle
  command/                          # status and completion
  config/                           # Paper-free strict settings
src/main/resources/                 # plugin.yml and config.yml
src/test/java/                      # JUnit tests
src/paperTestPlugin/                 # separate event-observation fixture
src/paperIntegration/java/           # standalone server process harness
.github/workflows/build.yml          # Linux/Windows build; optional Paper run
docs/                               # design, scope and evidence
```

Future storage, model, capture, query and rollback packages follow [architecture responsibilities](ARCHITECTURE.md#responsibilities-and-data-flow). Keep one project until concrete code/test constraints justify a split. Do not add unused JDBC, pooling or ORM dependencies to the bootstrap. When SQLite arrives, preserve native driver resources/notices, verify engine version and package/load it on Windows and Linux.

## Engineering conventions

- Keep domain logic immutable and Paper-free. Use descriptive names, explicit constructor dependencies and small interfaces at tested boundaries.
- Document thread ownership. No live Paper objects on storage workers, and no blocking I/O/future waits in capture or world-apply callbacks.
- Use stable UUIDs, versioned complete snapshots, ordered capture identities and UTC timestamps. Keep partial/unknown evidence explicit.
- Bind SQL values and bound allocations, queues, plans and query results when these systems are introduced. Treat overload and partial failure as explicit outcomes.
- Append released migrations; never edit already-applied versions. Test upgrade/recovery before expanding persisted state.
- Use four-space Java indentation, UTF-8 and consistent line endings from `.gitattributes`. Compilation currently checks all javac lint warnings; no separate formatter is configured yet.
- Test invariants with independent expectations and preserve bug reproducers. Update requirements and status claims alongside behavioural changes.
- Keep local worlds, databases, generated artifacts and credentials out of source control. Use disposable data for every destructive test.

## CI and review

The build workflow pins action revisions, runs JDK 25 builds on Linux and Windows, verifies the Wrapper, and uses read-only repository permissions. Ordinary PR/push builds never start Minecraft. A manually dispatched workflow can run Paper tests only when its EULA acceptance input is explicitly selected. CI configuration is implemented; do not claim a remote run passed until GitHub has actually run it.

Workflow artifacts retain JARs, unit-test reports and runtime console logs for seven days, including failed runs. World data is not uploaded. Local Windows unit and Paper results are recorded in [TESTING.md](TESTING.md#current-build-evidence).

Run relevant checks, inspect the packaged artifact and review [CONTRIBUTING.md](../CONTRIBUTING.md) before a PR. Report exactly which checks ran and which were unavailable. Future logging/recovery claims require the relevant real-server and fault-injection evidence; successful bootstrap tests do not complete Milestone 1.
