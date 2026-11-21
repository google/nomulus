---
name: java-ast-refactoring
description: "AST-aware Java refactoring using OpenRewrite. Use when asked to structurally refactor Java code, change class names, change method signatures/overloads, replace builder patterns, modify annotations, or perform cross-file structural replacements. Note: Renaming fields or local variables/parameters is not supported natively via simple YAML recipes in the standard openrewrite modules. Don't use for: formatting-only fixes (use spotlessApply / javaIncrementalFormatApply), non-Java files, or single-line edits that a direct file edit handles safely."
---

# AST-Aware Java Refactoring

This skill uses OpenRewrite to perform Abstract Syntax Tree (AST) based refactoring on Java codebases. This is highly preferred over text-based regex or python scripts because it understands Java semantics, correctly updates imports, and preserves formatting.

## Parameter and Field Renaming (Last Resort)

Because OpenRewrite's YAML recipes do not natively support simple variable or field renaming, a custom script is provided:
```bash
python3 .gemini/skills/java-ast-refactoring/scripts/safe_rename.py <filepath> <old_name> <new_name>
```
**CRITICAL:** Running this python script is a LAST RESORT. It is a regex-based token replacement that ignores strings and comments, but it lacks true AST understanding. ALWAYS prefer using OpenRewrite recipes (`rewrite.yml`) for structural changes like renaming classes, methods, or moving targets, as OpenRewrite correctly handles imports, types, and cross-file references safely.

## Usage

1. Start from a clean working tree (`git status`) so the refactoring diff is isolated and easy to revert.
2. Create a `rewrite.yml` recipe file in the workspace root. Refer to `.gemini/skills/java-ast-refactoring/references/rewrite_recipes.md` for syntax. The recipe's first `name:` line is the recipe that gets activated.
3. Execute the script from the repository root:
```bash
./.gemini/skills/java-ast-refactoring/scripts/run_rewrite.sh rewrite.yml
```
   A recipe stored elsewhere is copied to `./rewrite.yml` for the run and removed afterwards; a recipe already at `./rewrite.yml` is used in place and left untouched. Delete your own `./rewrite.yml` before committing.
4. The script will safely apply the AST transformations and then automatically run `./gradlew spotlessApply` and `./gradlew javaIncrementalFormatApply` on the modified files to automatically fix any Checkstyle line-length and import ordering issues caused by longer/shorter identifiers.

## Verification (Mandatory)

1. Review the full diff (`git diff --stat` and `git diff`) and confirm every change is intended. Revert unrelated edits.
2. Compile everything after running OpenRewrite to ensure no compilation errors were introduced:
```bash
./gradlew build -x test -x :console-webapp:buildConsoleWebapp
```
3. Run the tests covering the touched code, e.g. `./gradlew :core:standardTest -x :console-webapp:buildConsoleWebapp`.
4. Confirm `git status` shows no stray files (`rewrite.yml`, `rewrite-init.gradle`, or a modified `console-webapp/package-lock.json`).
5. Commit, then run the `pr-polisher` skill (`python3 .gemini/skills/pr-polisher/scripts/check_diff.py`) to catch leftover fully qualified names, unused imports, and other anti-patterns.

## Error Recovery

*   **Recipe not activated / "Could not extract 'name:'":** Ensure the recipe has a top-level `name:` line and `type: specs.openrewrite.org/v1beta/recipe`.
*   **No changes made:** The `methodPattern` or type name likely does not match. Use fully qualified types, `..` for any parameters, and verify against the method patterns section of `references/rewrite_recipes.md`.
*   **Gradle or plugin resolution failure:** Re-run with `--stacktrace`. The init script pins the OpenRewrite plugin version; if it is incompatible with the current Gradle version, update the version in `scripts/run_rewrite.sh`.
*   **Formatter failures after the run:** Re-run `./gradlew spotlessApply javaIncrementalFormatApply`, then repeat the compile step.
*   **Interrupted run:** Remove leftover `rewrite-init.gradle` (and `./rewrite.yml` if it was a temporary copy), then restart.
*   **Bad transformation:** Discard it with `git checkout -- <paths>` (or `git stash`), refine the recipe, and re-run rather than hand-patching the output.

## Known Limitations & Troubleshooting
*   **Static Imports Dropped on Class Rename:** When using `ChangeType` to rename a class, OpenRewrite may sometimes drop static imports for fields/constants belonging to the old class instead of updating them to the new class. If compilation fails due to "cannot find symbol" for a constant after a class rename, manually restore the static import (e.g., `import static com.new.ClassName.CONSTANT;`).
*   **Continuous Improvement:** If any new issues or edge cases are found while running the refactoring (e.g., build failures, formatting issues, or missed transformations), you **MUST** proactively ask the user if you should permanently update this skill file (`SKILL.md`) and its accompanying scripts (`scripts/run_rewrite.sh`, `scripts/safe_rename.py`) to fix the issue for future use. Do not wait for the user to prompt you to fix the infrastructure.
