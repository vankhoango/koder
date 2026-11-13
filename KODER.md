# Koder

A coding agent written in Kotlin. Package: `net.khoango.koder`.
JDK 21, Gradle with the Kotlin DSL, dependencies in `gradle/libs.versions.toml`.

## Commands (PowerShell)

- Build: `.\gradlew build`
- Run the tests: `.\gradlew test`
- Install the launcher: `.\gradlew installDist`. Koder runs from `build\install`,
  so never delete `build` or run `clean` while Koder is running.

## Structure (under `src/main/kotlin/net/khoango/koder/`)

- `agent/`: the agent loop (`Agent`) and its events
- `llm/`: the `LlmClient` interface and `ChatCompletionsClient` (OpenAI Java SDK)
- `tools/`: one file per tool, e.g. `ReadFileTool.kt`; shared helpers in `Tool.kt`
- `permissions/`: `PermissionPolicy`
- `prompt/`: system prompt and memory files

## Conventions

- Tools never throw for expected problems: they return `failure("…")` with a hint for the model.
- Tests use `FakeLlmClient` and never call a real model.