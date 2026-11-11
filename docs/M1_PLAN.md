# Milestone 1 implementation plan

Scope: issues [#1](https://github.com/D3V-X3N0/Chronicle/issues/1), [#2](https://github.com/D3V-X3N0/Chronicle/issues/2), [#3](https://github.com/D3V-X3N0/Chronicle/issues/3) and [#4](https://github.com/D3V-X3N0/Chronicle/issues/4). No lookup, recovery, or release work.

1. Qualify stone/air transitions using a loopback network client and the pinned disposable Paper server. Exercise survival/creative, protection cancellation, build denial, replacement/no-op and repeated changes. Keep observations separate from successful history. Document evidence and exclusions before connecting capture.
2. Package a pinned Xerial JDBC driver and native resources. Add a connection-owned, checksummed transactional schema runner, verified pragmas, sessions and conservative startup/reopen failure handling. Validate actual on-disk databases and the packaged artifact on Windows/Linux.
3. Add immutable bounded groups, a single writer, atomic prepared transactions, exact-content idempotency, finite busy retry, aggregate coverage incidents, status snapshots and deadline-bound shutdown. Test real locks, injected failures and process death at transaction boundaries.
4. Connect only qualified capture, using bounded ordered reconciliation and explicit uncertainty. Assert actual committed rows after a real-client run and restart. Update operational documentation, issue checklists and milestone status only from observed results.

Validation: JDK 25 `clean build`; opted-in local Paper smoke and packet-client tests; documentation/link checks; ordinary commits/pushes and Linux/Windows CI. Any unpassed acceptance gate remains open with evidence. Development defaults are bounds, not measured capacity promises.

Completed on 11 November 2026: all four steps, 54 build tests, both local smoke cases and ten local packet scenarios with exact history after restart. Linux and Windows CI passed the final code revision `61e41e2`, including packaged-native loading. M1 is complete for the documented stone/air subset; no M2 or recovery work was included. See [current evidence](TESTING.md#current-m1-evidence).
