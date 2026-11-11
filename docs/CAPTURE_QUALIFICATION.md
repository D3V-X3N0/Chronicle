# M1 capture qualification

Local evidence, 11 November 2026: Windows 11, JDK 25.0.1, Paper 26.3 build 248 (the SHA-256 in `gradle/paper-test.properties`). `paperPacketTest -PacceptMinecraftEula=true` passed all ten scenarios. The initial passing run was `packets-6555563526290782497`; generated logs/worlds are not checked in. Later integration results are recorded in [TESTING.md](TESTING.md).

## Reproduction and independent oracle

Run `./gradlew.bat paperPacketTest -PacceptMinecraftEula=true` on Windows or `bash ./gradlew paperPacketTest -PacceptMinecraftEula=true` on Linux/macOS with JDK 25, after accepting the Minecraft EULA. No other client/runtime dependency is needed. The task creates a fresh loopback-only server under `build/paper-tests/`, verifies the pinned server checksum and uses a controlled offline test identity. Authentication interoperability is outside this test. RCON/query are disabled; the server has no public bind and never opens an existing world.

`PacketClient` speaks protocol 777 over a TCP socket. Login, configuration, teleport acknowledgements, placement and digging are real client packets processed by the server. It does not construct or dispatch Bukkit events. Packet IDs and field layouts were inspected from the pinned runtime's protocol classes, including 26.3's changed teleport acknowledgement and digging enum. This code lives only in the test harness; the distributable uses public Paper APIs.

The separate `PacketFixture` prepares a small stone arena at Y=79, sets the player's inventory/mode/location and observes at MONITOR. Its controlled protection handlers cancel or deny at HIGHEST. This qualifies that test protection implementation, not every third-party protection plugin. The harness has independent expected event counts, world outcomes, player UUID and same-tick ordering assertions. A missed marker, wrong outcome, disconnection or timeout fails the test and terminates its server process. Delays allow actions to complete but never determine success.

The offline test identity is `ChronicleTest`, UUID `c7be6472-1205-3de6-8420-d3a66f244ce5`; it is generated fixture data, not a real user's profile.

## Observed contract

| Packet action | Before / callback state | Later world | Decision |
| --- | --- | --- | --- |
| Creative and survival place stone into air | air / stone | stone | Eligible only after corroborating evidence |
| Creative and survival break stone | stone / stone | air | Callback is pre-destruction; air must be observed, never invented |
| Protection-cancelled place | air / stone, cancelled | air | No successful history |
| Build-denied place | air / stone, canBuild=false | air | No successful history |
| Protection-cancelled break | stone / stone, cancelled | stone | No successful history |
| Replace short grass with stone | short_grass / stone | stone | Explicitly unsupported in M1 |
| Dig air | air / air; one break callback | air | Proven no-op, no successful history |
| Same-tick break then place | stone / stone, then air / stone | stone | Two ordered actions; next placement's replaced-state supplies intermediate air |

The initial passing repeated-action fixture produced both callbacks at tick 191. Its final stone sample cannot represent the break's post-state. The reconciler uses the next observation's **pre-state** for the earlier action, then validates the final action with a later sample. It never replaces both transitions with a net change.

## Qualified boundary and limitations

The M1 eligible set is exactly `minecraft:air -> minecraft:stone` placement and `minecraft:stone -> minecraft:air` breaking, with direct player UUIDs and complete canonical block-data strings for these two states (codec 1, Minecraft data version captured from the runtime). Each accepted action has a distinct ordered capture sequence. The same listener owns ordinary and multi-place dispatch; multi-place is unsupported, with no partial successful group. All history remains ineligible for recovery.

Observations resolve in capture order. A matching next pre-state or bounded later sample corroborates the candidate transition. An unchanged observed state is a no-op; a different, unavailable or expired state creates an explicit coverage gap. Pending observations have a hard count limit and two-tick expiry, with at most 128 later samples per tick. Capture and reconciliation use only the owning server thread and never load chunks. Storage admission has separate record/byte limits, including in-flight work.

This is a qualified public-event observation contract, not a universal world transaction log. A plugin that changes events after MONITOR, directly changes blocks without events, or changes a block away and back between observations can defeat attribution/finality. The test setup contains only Chronicle and its controlled fixture. Containers, block entities, multi-block structures, physics, fluids and all other materials are unsupported; unsupported actions produce aggregate global coverage incidents. Missing events cannot be detected universally. Neither complete server coverage nor reversibility is claimed.

Pure reconciliation tests cover cancelled/denied attempts, no-ops, unsupported replacement/multi-place, ambiguous/unavailable/expired states, overflow, shutdown and ordering across locations. Synthetic dispatch remains a separate lifecycle/routing test and is not the evidence above.

## M2 investigation qualification

M2 retains the same successful transition/state list and recovery exclusion. Its
packet harness adds a second separately loaded protection fixture cancelling both
action types at each legitimate priority (LOWEST through HIGHEST). It also checks
paginated investigation before/after restart, post-admission permission revocation,
protected stone/chest inspection with held items, left/right clicks and offhand,
and validated reload. See [INVESTIGATION.md](INVESTIGATION.md) for the exact interaction
boundary and [current M2 evidence](TESTING.md#current-m2-evidence) for validation.

These controlled plugins do not establish compatibility with every third-party
protection implementation. Later handlers that reverse Chronicle's inspection denials
at HIGHEST, or violate MONITOR conventions, remain outside the qualification.
