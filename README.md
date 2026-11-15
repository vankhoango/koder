# Koder

A coding agent for the terminal, written in Kotlin — built step by step as a learning project about
**agentic software development**.

*I wanted to master AI coding agents. To truly master them, you need to understand how they work under
the hood — and the best way to learn that is to build one yourself. So here is my own coding agent,
inspired by Claude Code: built in Kotlin with the OpenAI Java SDK, and running on Google's Gemma 4 model
locally on my machine. If you're curious about how agents work, I hope it helps you learn about AI agent
development too.*

Koder talks to any OpenAI-compatible model (such as ChatGPT, or a local model in LM Studio), reads and edits
files, runs commands, follows reusable *skills*, and uses tools from *MCP servers* — with permissions,
streaming output, automatic context management, and saved sessions.

> **Note:** Koder is a learning project. It's meant to show how coding agents work inside, not to replace
> production tools. Read the [safety notes](#safety) before letting it work on code you care about.

## Features

- **Agent loop** with streaming output, a configurable step limit, and clean recovery from errors
- **File tools:** read, list, find (glob), search (regex), write, and edit — with project-boundary checks,
  read-before-write protection, change detection, atomic writes, and preserved line endings
- **Shell commands** (PowerShell on Windows, bash elsewhere) with timeouts and output limits
- **Permissions:** safe actions run automatically, risky ones ask first, dangerous ones are blocked;
  "always" approvals per session, and a `--yolo` mode
- **Context management:** `/compact` and automatic compaction before the context window fills up
- **Project memory:** instructions from `KODER.md`, loaded into every session (`/init` writes one for you)
- **Sessions** saved after every turn, and resumed with `--resume`
- **Skills:** reusable instructions in `.koder/skills/`, with arguments, stacking, bundled scripts,
  selection by the model, and subagents
- **MCP client:** tools from MCP servers over stdio or Streamable HTTP, configured in `.koder/mcp.json`
- **Retries** with exponential backoff for temporary API failures
- **Tested** without network access, using a scripted fake model

## Requirements

- **JDK 21**
- An **API key** for an OpenAI-compatible model provider (for example, a Gemini API key from Google AI Studio).
  A local server like LM Studio works too.
- *Optional:* **Python 3.10+**, for the demo MCP server and for skills with Python scripts

## Getting started

### 1. Build

```powershell
.\gradlew installDist        # Windows
./gradlew installDist        # macOS / Linux
```

This creates a launcher in `build/install/koder/bin/`. Add that folder to your `PATH` to run `koder`
from any project folder.

### 2. Configure

Koder reads its settings from environment variables, which command-line options can override:

| Variable | Option | Default | Purpose |
|---|---|---|---|
| `KODER_API_KEY` | — | *(required)* | The API key for the model provider |
| `KODER_BASE_URL` | `--base-url` | Gemini's OpenAI-compatible endpoint | The provider's base URL |
| `KODER_MODEL` | `--model` | `gemini-3.5-flash` | The model to use |
| `KODER_CONTEXT_LIMIT` | `--context-limit` | *(off)* | The model's context window in tokens; enables automatic compaction |

For a local model in LM Studio, for example:

```powershell
$env:KODER_BASE_URL = "http://localhost:1234/v1"
$env:KODER_MODEL = "<model identifier from LM Studio>"
$env:KODER_API_KEY = "any-value"     # LM Studio ignores it, but Koder requires one
```

### 3. Run

Start Koder in the folder of the project you want to work on:

```powershell
koder                       # interactive session
koder "Explain this project"  # start with a first question
koder --resume              # continue the most recent session
```

Other options: `--max-steps`, `--no-tools`, `--yolo`, and `--mcp` (see `koder --help`).

## Commands

| Command | Purpose |
|---|---|
| `/help` | List all commands, including skills |
| `/reset` | Start a new conversation |
| `/compact` | Summarize the conversation to free up context |
| `/init` | Explore the project and write `KODER.md` |
| `/skills` | List the available skills |
| `/preview <skill> [input]` | Show the message a skill would send, without running it |
| `/<skill> [+skill…] [input]` | Run a skill, optionally stacked with others |
| `/quit` | Exit Koder |

## Project memory: `KODER.md`

Instructions in `KODER.md` (in the project root) and `~/.koder/KODER.md` (personal) are added to every
session: build commands, project structure, conventions. Keep them short and specific — they're sent
with every request.

## Skills

A skill is a folder with a `SKILL.md` file: a short frontmatter, followed by instructions in Markdown.
Skills live in `.koder/skills/<name>/` (project) or `~/.koder/skills/<name>/` (personal).

```markdown
---
name: code-review
description: Review Kotlin code for bugs and this project's conventions. Use when the user asks for a code review.
argument-hint: [files to review]
subagent: true
allowed-tools: read_file, find_files, search_text, run_command
---
# Code review

1. Files to review: $ARGUMENTS
   If no files are given, review the uncommitted changes, one file at a time with `git diff -- <file>`.
2. …
```

| Field | Meaning |
|---|---|
| `name`, `description` | Required. The model sees only these until the skill is used. |
| `argument-hint` | Shown by `/help` |
| `subagent` | `true` runs the skill in a separate agent, which returns only a report |
| `allowed-tools` | The tools a subagent may use |

`$ARGUMENTS` is replaced with the text after the command. A skill's folder can also contain scripts,
which its instructions tell the model to run. The format follows the conventions of Agent Skills.

This repository includes example skills in `.koder/skills/`: `code-review`, `commit-message`,
`kotlin-style`, and `test-report`.

## MCP servers

Koder connects to MCP servers that speak protocol revision **2026-07-28**. Configure them in
`.koder/mcp.json` (project) or `~/.koder/mcp.json` (personal), in the format used by other MCP clients:

```json
{
  "mcpServers": {
    "demo": {
      "command": "mcp-demo/.venv/Scripts/python",
      "args": ["mcp-demo/server.py"]
    },
    "remote-example": {
      "type": "http",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer ${EXAMPLE_TOKEN}" }
    }
  }
}
```

`${NAME}` is replaced with the environment variable `NAME`, so tokens stay out of the file.
A server's tools appear to the model as `mcp__<server>__<tool>`.

### The demo server

`mcp-demo/` contains a small Python server with two tools, `add` and `word_count`:

```powershell
py -m venv mcp-demo\.venv
mcp-demo\.venv\Scripts\python -m pip install -r mcp-demo\requirements.txt
```

It runs over stdio by default, or over HTTP with `--http` (and optionally `--port`):

```powershell
mcp-demo\.venv\Scripts\python mcp-demo\server.py --http
```

## Safety

An agent that can edit files and run commands can also damage your work. Koder includes several
protections, but none of them is a sandbox:

- **Commit your work before a session.** `git diff` shows what changed, and `git checkout` undoes it.
- **Writes, edits, and most commands ask for permission.** Read-only actions and a few safe commands
  (like `git status` or `./gradlew test`) run without asking.
- **`--yolo` allows everything except a short list of dangerous commands.** Only use it in a sandbox,
  such as a container or a virtual machine.
- **Project configuration is untrusted.** Koder asks before starting MCP servers from a project's
  `.koder/mcp.json`. Skills and `KODER.md` in a downloaded project are prompts written by someone else:
  review them before use.
- **Sessions are saved in `.koder/sessions/`** and can contain the contents of files Koder read.
  The folder is excluded from Git.

## Project structure

```
src/main/kotlin/net/khoango/koder/
├── Main.kt           command-line options
├── app/              assembles everything (KoderApp)
├── agent/            the agent loop, events, and subagents
├── cli/              chat loop, commands, and console output
├── domain/           messages and tool definitions
├── llm/              the LlmClient interface, the Chat Completions client, and retries
├── mcp/              MCP client: JSON-RPC, stdio and HTTP transports, configuration
├── permissions/      the permission policy
├── prompt/           the system prompt and memory files
├── session/          saved sessions
├── skills/           loading and running skills
└── tools/            the built-in tools
```

## Testing

```powershell
.\gradlew test
```

The tests use a scripted fake model and temporary folders, so they need no network access and no API key.
Tests that use the Python demo server are skipped if its virtual environment isn't set up.

## Limitations

- Only MCP protocol revision **2026-07-28** is supported; servers that speak only older revisions are rejected.
- MCP servers that require OAuth sign-in aren't supported yet; servers that accept a token in a header are.
- Developed and tested mainly on Windows.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).