# Local development environment

- Local-only tool paths and credentials live in the ignored root `local.properties`.
- Before searching the machine or downloading tools, read these keys without printing secrets:
  - `java.home`
  - `mqtt.client.dir`
  - `adb.path`
  - `emulator.path`
  - `sdk.dir`
- Run Gradle through `tools/run-gradle.ps1`; it resolves `java.home` automatically.
- Robot simulator scripts resolve Mosquitto from `mqtt.client.dir` automatically.
- Never print or commit `mqtt.password`, `mqtt.robot.password`, or other values from `local.properties`.
- Project-local binaries belong under the ignored `.local-tools/` directory.

## Token-efficient builds and releases

- Start each long-running local build or GitHub Actions workflow only once.
- Do not poll, watch, sleep-loop, or repeatedly query build/release status.
- After starting a remote workflow, report its run URL/ID and let the user monitor it; continue verification only after the user reports completion.
- If a foreground command returns a background session, do not repeatedly fetch logs unless the user explicitly asks for a status check.

## Delivery and handoff

- Test every code change before deployment. For Android changes, use the normal project workflow: unit tests, lint, and an installable build; avoid unnecessary defensive complexity.
- Keep Android behavior and UI aligned with platform conventions, standard visual quality, and clear user-facing feedback.
- Keep the repository reproducible for another developer; document required tools, configuration keys, build commands, and current branch baseline without committing secrets.
- After each completed change, refresh the repository handoff/status documentation to describe only the current valid state. Replace stale values instead of accumulating historical notes.
