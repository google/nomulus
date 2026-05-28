---
name: java-ast-refactoring
description: "AST-aware Java refactoring using OpenRewrite. Use when asked to structurally refactor Java code, change class names, change method signatures/overloads, replace builder patterns, modify annotations, or perform cross-file structural replacements. Note: Renaming fields or local variables/parameters is not supported natively via simple YAML recipes in the standard openrewrite modules."
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

1. Create a `rewrite.yml` recipe file in the workspace root. Refer to `.gemini/skills/java-ast-refactoring/references/rewrite_recipes.md` for syntax.
2. Execute the script:
```bash
./.gemini/skills/java-ast-refactoring/scripts/run_rewrite.sh rewrite.yml
```
3. The script will safely apply the AST transformations and then automatically run `./gradlew spotlessApply` and `./gradlew javaIncrementalFormatApply` on the modified files to automatically fix any Checkstyle line-length and import ordering issues caused by longer/shorter identifiers. Verify the output using `git diff`.
4. **MANDATORY:** Always run `./gradlew build -x test` (or the equivalent compile task) after running OpenRewrite to ensure no compilation errors were introduced.

## Known Limitations & Troubleshooting
*   **Static Imports Dropped on Class Rename:** When using `ChangeType` to rename a class, OpenRewrite may sometimes drop static imports for fields/constants belonging to the old class instead of updating them to the new class. If compilation fails due to "cannot find symbol" for a constant after a class rename, manually restore the static import (e.g., `import static com.new.ClassName.CONSTANT;`).
*   **Continuous Improvement:** If any new issues or edge cases are found while running the refactoring (e.g., build failures, formatting issues, or missed transformations), you **MUST** proactively ask the user if you should permanently update this skill file (`SKILL.md`) and its accompanying scripts (`scripts/run_rewrite.sh`, `scripts/safe_rename.py`) to fix the issue for future use. Do not wait for the user to prompt you to fix the infrastructure.
