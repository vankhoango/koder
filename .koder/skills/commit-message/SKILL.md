---
name: commit-message
description: Write a Git commit message for the current changes. Use when the user asks for a commit message.
---
# Commit message

1. Run `git diff --staged`. If nothing is staged, run `git diff` instead.
2. Write a message with:
    - a subject line in the imperative mood ("Add…", "Fix…"), at most 60 characters;
    - a blank line;
    - a short body explaining *why* the change was made, if that isn't obvious.
3. Show the message to the user. Don't run `git commit` yourself.