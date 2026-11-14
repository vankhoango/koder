---
name: test-report
description: Run the tests and explain any failures. Use when the user asks to run the tests or why tests fail.
argument-hint: [extra instructions]
---
# Test report

1. Run the tests with `.\gradlew test`. If tests fail, the command fails too; that's expected, so continue.
2. Summarize the results with this skill's script, using the Python command from the environment section:
   `<python> .koder/skills/test-report/test_report.py`
3. For each failure, read the failing test and the code it tests, and explain the most likely cause
   in one or two sentences.
4. Don't change any files.