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
