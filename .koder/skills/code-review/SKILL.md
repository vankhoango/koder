---
name: code-review
description: Review Kotlin code for bugs, readability, and this project's conventions. Use when the user asks for a code review of files or of uncommitted changes.
argument-hint: [files to review]
subagent: true
allowed-tools: read_file, find_files, search_text, run_command
---
# Code review

1. Find what to review.
   - Files to review: $ARGUMENTS
   - If no files are given, run `git diff --stat` to see which files changed. Then review them
     one at a time with `git diff -- <file>`, so that no diff is truncated.
   - Read the full file when you need more context than the diff shows.

2. Check for:
   - bugs and unhandled edge cases;
   - tools that throw instead of returning `failure(...)` with a hint for the model;
   - missing or weak tests (tests must use `FakeLlmClient`, never a real model);
   - unclear names, and duplicated code.

3. Only report issues you can point to in code you have actually read, and quote the relevant line.
   Never mention functions, files, or behavior you haven't seen. If you're unsure whether something
   is handled elsewhere, search for it with `search_text` before reporting it.

4. Report in this order:
   - **Summary:** one or two sentences.
   - **Issues:** `file:line`, severity (high, medium, low), the quoted line, and what to change.
   - **Suggestions:** optional improvements.

Be specific and brief. Don't change any files; only report.