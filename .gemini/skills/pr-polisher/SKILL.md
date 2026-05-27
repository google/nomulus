---
name: pr-polisher
description: Automated pre-flight checklist to polish PRs. Use this before declaring a task or PR complete to automatically verify license headers, commit hygiene, formatting, and codebase mandates.
---

# PR Polisher

This skill runs an exhaustive, automated pre-flight checklist against the repository to ensure all changes conform to Nomulus's strict engineering mandates.

## When to Use

You MUST activate and execute this workflow immediately before you are about to declare a PR, task, or codebase refactor "done" or ready for human review. Do not declare the task complete until this workflow succeeds with 0 errors.

## Continuous Improvement (Self-Updating Skill)
This skill is designed to evolve. If a human code reviewer (or presubmit hook) points out a deficit, or if you (the agent) independently catch a recurring mistake, anti-pattern, or convention violation:
1. **Consider if the check is suitable for automation.** Not every mistake can or should be caught by a Python script.
2. If suitable, modify `.gemini/skills/pr-polisher/scripts/check_diff.py` to add a new regex check for the missed pattern, or update this `SKILL.md` file with a new validation step.
3. Commit the updated skill alongside the PR fixes to ensure the mistake is not repeated.

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

3. **Run Presubmits and Compilation**
   Ensure that the project builds correctly and all presubmit checks pass. Use scoped builds when possible to save time and avoid unwanted side effects (like modifying `console-webapp/package-lock.json`).
   ```bash
   # Run presubmits
   ./gradlew runPresubmits

   # Verify compilation (use a scoped build if you only modified one module, e.g., :core)
   ./gradlew :core:build -x test
   # Run standard test suite if modifying core
   ./gradlew :core:standardTest
   ```

4. **Verify Test Coverage Additions**
   Review your diff (`git diff HEAD^`). If you have added any *new* public methods or modified core logic, manually verify that you have added tests to the corresponding `Test.java` file. A code review is not thorough if it only checks for compilation.

5. **Address Errors, Amend, and Re-Run (Iterative Checking)**
   If any script throws an error, or if formatting changes were applied, you must stage those fixes and amend your commit:
   ```bash
   git add -u
   git commit --amend --no-edit
   ```
   **CRITICAL:** You must loop back to Step 1 and run `python3 ./pr-polisher/scripts/check_diff.py` again. Continue this loop of checking and amending until the script definitively returns `0 ERRORS`, the build/presubmits pass, and the working directory is perfectly clean. Do not assume your fixes worked without re-running the check.