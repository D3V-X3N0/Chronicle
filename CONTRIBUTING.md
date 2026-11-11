# Contributing to Chronicle

Chronicle has qualified stone/air player logging, SQLite history, bounded investigation and automated unit, database, crash and real-client harnesses. Recovery is not implemented. Useful contributions include reviewing capture/query limits, improving failure coverage and completing focused tasks from the [roadmap](docs/ROADMAP.md).

## Before starting

Read the [product scope](docs/PRODUCT_SPEC.md), [architecture](docs/ARCHITECTURE.md), and [development guide](docs/DEVELOPMENT.md). Correctness and recovery safety come before performance and breadth. Open an issue before a large new feature, dependency, backend, compatibility expansion, or change to persisted state so the scope and trade-offs can be reviewed.

Small documentation corrections can go straight to a PR. The [first development task](docs/ROADMAP.md#first-development-task) records the foundation and its qualification evidence; avoid starting several unfinished subsystems at once.

## Issues

Use the [bug report](.github/ISSUE_TEMPLATE/bug_report.md) or [feature request](.github/ISSUE_TEMPLATE/feature_request.md) template. Documentation/design defects are valid bugs; mark runtime fields as not applicable when the report does not concern runtime behaviour.

For future runtime bugs, give the Chronicle revision, exact Paper/Minecraft/Java versions, configuration changes, reproduction steps, expected and actual behaviour, and sanitized diagnostics. Recovery bugs should include operation state and the last known good world/history boundary. Do not post credentials, player IPs, private item text, or entire production databases/worlds in public issues. Preserve a backup before reproducing a destructive failure in a disposable environment.

## Pull requests

- Keep a PR focused on one reviewable change and explain the user-visible problem, resulting behaviour, trade-offs and test evidence.
- Link the relevant issue or requirement. Do not mark roadmap work complete merely because a class, listener or document exists.
- Follow the [engineering conventions](docs/DEVELOPMENT.md#engineering-conventions), preserve thread ownership and bounded work, and update affected documentation.
- Add a meaningful regression test for a behavioural fix. Persisted schema/codec changes need upgrade and failure tests; rollback changes need independent state comparisons.
- Run the applicable build/tests and state exactly what ran and what could not run. Validate documentation links/status and `git diff --check`, including new files. Actual commands and explicit opt-in requirements for the Paper harness are in the development guide; synthetic event fixtures do not qualify real-player capture.
- Do not include local servers, world saves, database files, secrets, generated binaries, unrelated formatting or dependency churn.

Review should verify cancellation, ordering, snapshot completeness, overload and interrupted-operation behaviour where relevant. New support claims require real Paper evidence; a mock or synthetic benchmark alone is insufficient. Follow-up fixes should remain understandable in the real history, without manufactured commits, users, benchmarks or release activity.

## License and collaboration

The repository already carries the [MIT license](LICENSE). Preserve its existing attribution. Submit only work you have the right to contribute under the project's license, and identify third-party code/assets and their notices. A licensing change is an owner decision, not part of routine contribution work.

Be specific and respectful in reviews. Explain concerns with examples or reproducible evidence, and help narrow uncertain features to a safe, testable scope.
