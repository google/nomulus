---
name: pr-polisher
description: Automated pre-flight checklist to polish PRs. Use this before declaring a task or PR complete to automatically verify license headers, commit hygiene, formatting, and codebase mandates.
---

# PR Polisher

This skill runs an exhaustive, automated pre-flight checklist against the repository to ensure all changes conform to Nomulus's strict engineering mandates.

## 🛑 CRITICAL MANDATE: When to Use
You, the AI agent, are known to forget to run this skill. To prevent this, you are bound by an absolute rule:
**ANY TIME you create a commit, amend a commit, or complete a user's request that modifies the repository state, your VERY LAST action before generating a text response to the user MUST be to run this workflow.**
Do not ask for permission. Do not wait for the user to remind you. Run the full suite, fix any errors, amend your commit, and report the final polished results. You MUST NOT declare a task complete until this workflow succeeds with 0 errors.

## Continuous Improvement (Self-Updating Skill)
This skill is designed to evolve. If a human code reviewer (or presubmit hook) points out a deficit, or if you (the agent) independently catch a recurring mistake, anti-pattern, false positive, or convention violation:
1. **You MUST proactively propose a fix to the user.** Do not wait for the user to instruct you to update the skill. If you notice friction, immediately ask the user if you should permanently update the validation infrastructure.
2. **Determine how to enforce the check.** Consider if the check is suitable for automation in the Python script. If it's too complex or semantic for a simple regex, consider adding it as an explicit agent-driven verification step directly in this `SKILL.md` file.
3. Update `.gemini/skills/pr-polisher/scripts/check_diff.py` to add a new automated check, OR modify this `SKILL.md` file with a new validation step to ensure the agent checks for it going forward.
4. Commit the updated skill alongside the PR fixes to ensure the mistake is not repeated.

## Workflow Execution Steps

1. **Run the Automated Analysis Script**
   Execute the packaged Python diff-checker script. This script automatically checks commit messages, working tree status, `package-lock.json` modifications, copyright years on new files, and a litany of anti-patterns using regex (e.g., fully-qualified names, incorrect clock injections, generic exception catching).

   ```bash
   python3 ./pr-polisher/scripts/check_diff.py
   ```

2. **Run Formatting Validation**
   Always run the project's formatting tools to ensure checkstyle and Google Java Format passes.
   ```bash
   ./gradlew spotlessCheck javaIncrementalFormatCheck
   # OR if formatting is needed:
   ./gradlew spotlessApply javaIncrementalFormatApply
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

4. **Verify PR Scope and Extraneous Files (Line-by-Line Inspection)**
   You must carefully review the entirety of your changes (`git diff HEAD^` or `git diff --cached`). Examine every single file and line changed to explicitly verify that the change *belongs* in this PR. You MUST look for and revert:
   * **Irrelevant changes:** Formatting or refactoring in files unrelated to the PR's core purpose.
   * **Accidental files:** Test output files, temp scripts, plan files (e.g., `codebase_review_plan.md`), scratchpads, or anything else generated during your workflow that shouldn't be committed.
   * **Unintended side effects:** Changes to configuration files like `package-lock.json` unless explicitly required.

5. **Verify Test Coverage Additions (Line-by-Line Inspection)**
   While looking at all the diffs, thoroughly check every single line to determine if any test changes or additions are necessary. If you have modified existing logic, added new branches, added a null check, or added new public methods, you MUST manually verify that the corresponding `Test.java` file covers these changes. If the test file or specific test cases do not exist, you must create them. A code review is not thorough if it only checks for compilation.

6. **Verify Commit Description Accuracy**
   Re-read your commit message (`git log -1 --pretty=format:%B`). Compare it directly against the actual diff (`git diff HEAD^`) to verify that it completely and accurately describes ONLY the changes present in the commit.
   * If the scope of the PR changed during prompting, you MUST update the commit message to reflect the final state of the code.
   * Ensure that the primary purpose of the PR is mentioned first, and that no irrelevant or outdated changes from previous attempts are listed.

7. **Address Errors, Amend, and Re-Run (Iterative Checking)**
   If any script throws an error, if scope was reduced, if the commit message was inaccurate, or if formatting changes were applied, you must stage those fixes and amend your commit:
   ```bash
   git add -u
   git commit --amend # Or git commit --amend --no-edit if only files changed
   ```
   **CRITICAL:** You must loop back to Step 1 and run `python3 ./pr-polisher/scripts/check_diff.py` again. Continue this loop of checking and amending until the script definitively returns `0 ERRORS`, the build/presubmits pass, and the working directory is perfectly clean. Do not assume your fixes worked without re-running the check.