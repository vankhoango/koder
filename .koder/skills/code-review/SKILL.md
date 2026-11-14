---
name: code-review
description: Review Kotlin code for bugs, readability, and this project's conventions. Use when the user asks for a code review of files or of uncommitted changes.
argument-hint: [files to review]
---
# Code review

1. Files to review: $ARGUMENTS
   If no files are given, run `git diff` to see the uncommitted changes, and review those.

2. Check for:
    - bugs and unhandled edge cases;
    - tools that throw instead of returning `failure(...)` with a hint for the model;
    - missing or weak tests (tests must use `FakeLlmClient`, never a real model);
    - unclear names, and duplicated code.
3. Report in this order:
    - **Summary:** one or two sentences.
    - **Issues:** `file:line`, severity (high, medium, low), and what to change.
    - **Suggestions:** optional improvements.

Be specific and brief. Don't change any files; only report.