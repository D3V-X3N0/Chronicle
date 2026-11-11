# Development guide

Status: M1 capture/storage and M2 investigation are implemented, 11 November 2026. The exact qualified subset and operational limits are in [CAPTURE_QUALIFICATION.md](CAPTURE_QUALIFICATION.md) and [STORAGE.md](STORAGE.md). Lookup and inspection are documented in [INVESTIGATION.md](INVESTIGATION.md); recovery is not implemented. The prepared first release is the evaluation [alpha candidate](releases/v0.1.0-alpha.1.md); publication is blocked on CI startup errors and stable-release gates remain unmet.

## Environment and pinned inputs

| Input | Pin |
| --- | --- |
| Java | JDK 25 toolchain and bytecode; no preview features |
| Paper API | `io.papermc.paper:paper-api:26.3.build.248-stable` (compile-only) |
| Paper test server | Minecraft 26.3, build 248; URL/SHA-256 in `gradle/paper-test.properties` |
| Gradle | 9.9.1, checksum-verified Wrapper/distribution |
| JUnit | Jupiter via BOM 6.1.3 |
| SQLite JDBC | Xerial 3.54.1.0, engine 3.54.1, bundled with native resources/notices |

Set `JAVA_HOME` to JDK 25. The build never installs a JDK. The Wrapper downloads Gradle; no global Gradle or extra client runtime is required. First dependency resolution needs internet. Cached builds can use `--offline`. Capture refuses other Paper builds/data versions rather than advertising unqualified compatibility.

Authoritative inputs: [Paper Maven metadata](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml), [server metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/248), [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [JUnit metadata](https://repo.maven.apache.org/maven2/org/junit/junit-bom/maven-metadata.xml) and [Xerial metadata](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml). Recheck primary sources before upgrading.

## Build and checks

Use `.\gradlew.bat` on Windows or `bash ./gradlew` on Linux/macOS before these task names:

| Task | Implemented behaviour |
| --- | --- |
| `clean build` | Compile with lint errors, run unit and SQLite/native/crash tests, check the artifact, compile/package fixtures |
| `test` | Pure configuration, permissions, reconciliation, observation-buffer, artifact and harness regression tests |
| `integrationTest` | Actual file-backed SQLite, schema/lifecycle, transaction/idempotency, writer bounds/failures, subprocess kills, isolated packaged-native loading |
| `paperTestPluginJar` | Separate disposable-server diagnostic/protection fixture |
| `paperIntegrationTest` | Opt-in real Paper lifecycle, synthetic dispatch and invalid-config preservation |
| `paperPacketTest` | Opt-in real TCP player actions, two protection fixtures, protected inspection, permissions, reload and exact SQL/command history after restart |
| `historyLoadTest` | Opt-in synthetic SQLite index and concurrent-write/query measurements; disposable 10k/100k/1m histories |

Lookup tests compare to an independent seeded oracle and check cursor ownership, cancellation and bounds. Recovery tasks and an automated publishing task do not exist. Alpha publication is manual after build/runtime CI, with an annotated tag, checksums and validation evidence. `historyLoadTest -PhistorySizes=10000,100000,1000000` runs the synthetic baseline; results under `build/load-tests/` are described in [M2_MEASUREMENTS.md](M2_MEASUREMENTS.md). Both ordinary test tasks run without Minecraft. Reports are at `build/reports/tests/test/index.html` and `build/reports/tests/integrationTest/index.html`, with XML under the corresponding `build/test-results/` directories.

Artifacts:

- `build/libs/chronicle-0.1.0-alpha.1.jar`: plugin, config, schemas, MIT license, third-party notice inventory and SQLite JDBC/native resources/notices. Only this JAR belongs in the evaluated server's plugins directory.
- `build/libs/chronicle-0.1.0-alpha.1-paper-test-plugin.jar`: test-only fixture; never install it on production.

The driver is loaded separately from Paper's older bundled JDBC. Packaging tests exercise an isolated classloader containing only the distributable and JDK classes. Both OS CI jobs load the actual native library. Archives have stable ordering/timestamps. The artifact test checks Java 25 bytecode, descriptor/version, permission defaults, license/notice inventory and exclusion of server/fixture classes.

Dependency versions/checksums are in `gradle.lockfile` and `gradle/verification-metadata.xml`. Review upstream changes before intentionally updating with `build --write-locks --write-verification-metadata sha256`. Never regenerate checksums to suppress an unexplained mismatch. Wrapper JAR SHA-256: `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`; its upstream notices remain intact.

JDK 25 can print a native-access warning when loading SQLite. `--enable-native-access=ALL-UNNAMED` is the documented JVM opt-in for native access; the current warning does not mean native loading failed. Chronicle still checks the effective engine/pragmas and refuses an unsuitable engine.

## Disposable Paper tests

Review and accept the [Minecraft EULA](https://www.minecraft.net/eula) before opting in:

```powershell
.\gradlew.bat paperIntegrationTest paperPacketTest -PacceptMinecraftEula=true
```

```sh
bash ./gradlew paperIntegrationTest paperPacketTest -PacceptMinecraftEula=true
```

Without acceptance, each harness fails before download or server-directory creation. The server download is pinned/checksummed. Runs create fresh worlds under `build/paper-tests/`, bind only to loopback, disable query/RCON and use at most 1 GiB heap. The packet harness uses an explicit offline test identity and an ephemeral port; it is not an authentication test. The smoke harness uses synthetic player events and does not qualify real-player behaviour.

The packet harness sends actual protocol-777 actions through the server, tests two separate controlled protection plugins across legitimate priorities, checks final world states and compares six exact committed records through SQL and paginated commands before/after restart. Player permission revocation, inspection with held items/chests, denial, offhand, logout and reload are also exercised. See [the qualification report](CAPTURE_QUALIFICATION.md) for scenarios and limits. No downloaded server, world, database or private player data is checked in. Logs remain beneath the disposable run directories. `clean` removes those generated logs/worlds, so preserve needed evidence first.

Runtime checks assert readiness/results with deadlines, not success from elapsed sleeps. Failed/expired runs terminate their child server. Tests use loopback-only disposable data, never production servers. Adding a new type/plugin interaction requires its own qualification; synthetic dispatch is insufficient.

## Repository organisation

```text
src/main/java/io/github/d3vx3n0/chronicle/
  bootstrap/       # lifecycle and explicit dependency wiring
  config/          # strict Paper-free settings
  capture/         # owning-thread Paper adapter and pure ordered reconciliation
  model/           # immutable copied evidence and groups
  storage/         # bounded writer, status and fixed-cardinality incidents
  storage/sqlite/  # connection ownership, schema, native-driver isolation
  query/           # immutable scopes, bounded readers/completions and cursor contexts
  command/         # permission-gated investigation, inspection, reload and status
src/main/resources/db/  # checksummed numbered SQL migration
src/test/java/          # unit, on-disk DB, native and subprocess checks
src/paperTestPlugin/    # separate synthetic/packet fixture plugin
src/paperIntegration/   # disposable-process harness and minimal real client
```

Keep one Gradle project and explicit dependencies. No ORM, connection pool, remote backend, custom concurrency primitive or recovery subsystem is introduced in M2.

## Engineering conventions

- Keep domain records immutable and Paper-free. Only the owning server thread reads world/player/block APIs.
- Never perform database/file/network I/O or wait on a future in capture. One writer and two query workers each own their SQLite connection; the shutdown wait is bounded and requires no Paper callback.
- Preserve distinct sequence/group identities. Unknown state produces coverage uncertainty, never guessed air/empty data.
- Bound records, payloads, reconciliation and batches. Failed admission cannot evict accepted history. Enqueue is not commit.
- Append migrations after schema release; retain exact idempotency content and test failures/reopen before widening persisted state.
- Use four-space Java indentation, UTF-8 and final newlines. Follow `.gitattributes`; do not add generated artifacts/private data.
- Add independent regression expectations for behaviour changes; label process-crash evidence separately from power-loss claims.

## CI and review

The pinned-action workflow runs JDK 25 `clean build` on Linux and Windows with read-only repository permissions. Ordinary push/PR builds do not start Minecraft. Manual workflow dispatch starts both Paper harnesses only with explicit EULA acceptance. Logs and test reports are retained for seven days; worlds/databases are not uploaded.

Record actual commit/run results in [TESTING.md](TESTING.md#current-m2-evidence). Future M3 work requires its own acceptance gates. See [CONTRIBUTING.md](../CONTRIBUTING.md) for review and licensing expectations.
