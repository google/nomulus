---
name: pr-polisher
description: Automated pre-flight checklist to polish PRs. Use this before declaring a task or PR complete to automatically verify license headers, commit hygiene, formatting, and codebase mandates.
---

# PR Polisher

This skill runs an exhaustive, automated pre-flight checklist against the repository to ensure all changes conform to Nomulus's strict engineering mandates.

## When to Use

You MUST activate and execute this workflow immediately before you are about to declare a PR, task, or codebase refactor "done" or ready for human review. Do not declare the task complete until this workflow succeeds with 0 errors.

## Workflow Execution Steps

1. **Run the Automated Analysis Script**
   Execute the packaged Python diff-checker script. This script automatically checks commit messages, working tree status, `package-lock.json` modifications, copyright years on new files, and a litany of anti-patterns using regex (e.g., fully-qualified names, incorrect clock injections, generic exception catching).

   ```bash
   python3 ./pr-polisher/scripts/check_diff.py
   ```

2. **Run Formatting Validation**
   Always run the project's formatting tools to ensure checkstyle passes.
   ```bash
   ./gradlew spotlessCheck
   # OR if formatting is needed:
   ./gradlew spotlessApply && ./gradlew javaIncrementalFormatApply
   ```

3. **Verify Test Coverage Additions**
   Review your diff (`git diff HEAD^`). If you have added any *new* public methods or modified core logic, manually verify that you have added tests to the corresponding `Test.java` file. A code review is not thorough if it only checks for compilation.

4. **Address Errors & Amend**
   If any script throws an error, or if formatting changes were applied, you must stage those fixes and amend your commit:
   ```bash
   git add -u
   git commit --amend --no-edit
   ```
   Loop back to Step 1 until the `check_diff.py` script returns `0 ERRORS` and the working directory is clean.