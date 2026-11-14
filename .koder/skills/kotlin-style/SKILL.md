---
name: kotlin-style
description: Koder's Kotlin style rules. Use together with other skills when writing or reviewing Kotlin code.
---
# Kotlin style rules

- Never use `!!`. Use `?:` with a clear error, `requireNotNull`, or `checkNotNull`.
- Prefer expression bodies (`fun x() = …`) for functions with a single expression.
- Name booleans like questions: `isError`, `hasText`, `wasRead`.
- Keep functions short (about 30 lines at most); extract helpers with clear names.
- Name boolean arguments at call sites: `edit(…, replaceAll = true)`.