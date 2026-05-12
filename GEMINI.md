# Engineering Standards for Gemini CLI

This document outlines foundational mandates, architectural patterns, and project-specific conventions to ensure high-quality, idiomatic, and consistent code from the first iteration. When modifying this file, always review the full document to prevent the introduction of duplicate instructions and ensure the content remains coherent and logically organized.

## Core Mandates

### 1. Rigorous Import Management
- **Addition:** When adding new symbols, ensure the corresponding import is added.
- **Removal:** When removing the last usage of a class or symbol from a file (e.g., removing a `@Inject Clock clock;` field), **immediately remove the associated import**. Do not wait for a build failure to identify unused imports.
- **No Redundant Qualifications:** NEVER use fully qualified class names (e.g., `java.time.temporal.ChronoUnit.DAYS`) in code when an import can be used instead. Always prefer adding an import and using the simple name.
- **Static Imports for Utilities:** Always statically import methods from utility classes like `DateTimeUtils` or `CacheUtils`. (e.g., use `toInstant(...)` instead of `DateTimeUtils.toInstant(...)`).
- **Checkstyle:** Proactively fix common checkstyle errors (line length > 100, formatting, unused imports) during the initial code write. Do not wait for CI/build failures to address these, as iterative fixes are inefficient.
- **Verification**: Before finalizing any change, scan the imports section for redundancy.
- **License Headers**: When creating new files, ensure the license header uses the current year (e.g., 2026). Existing files should retain their original year.

## 2. Time and Precision Handling (java.time Migration)

- **Idiomatic java.time Usage:** Avoid redundant conversions between `Instant` and `DateTime`. If a field or parameter is an `Instant`, use it directly. Do not convert to `DateTime` just to call a deprecated method if an `Instant` alternative exists or can be easily created. Furthermore, you should not call `toInstant()` or `toDateTime()` conversion methods when not strictly necessary; always prefer to use an alternative method that returns the correct type if one exists (e.g. use `tm().getTxTime()` which returns an `Instant` instead of calling `tm().getTransactionTime().toInstant()`).
- **CRITICAL MISTAKES TO AVOID:**
    - NEVER use `toInstant(clock.nowUtc())` or `toInstant(fakeClock.nowUtc())`. Both `Clock` and `FakeClock` have a `now()` method that natively returns a `java.time.Instant`. You MUST use `clock.now()` or `fakeClock.now()` directly.
    - NEVER double-wrap conversions like `toInstant(toDateTime(...))` or `toDateTime(toInstant(...))`.
    - NEVER mark method parameters or local variables as `final` unnecessarily, as it clutters the codebase. For class fields and constants, use `final` where applicable (i.e. when the field is assigned once and never mutated) to enforce and communicate immutability.
    - When using test helpers like `assertThatCommand().atTime(...)` or `ForeignKeyUtils.loadResource(...)`, ALWAYS use the `Instant` overloads. DO NOT wrap `Instant` instances in `toDateTime(...)` just to pass them to deprecated overloads.
- **UTC Timezones:** Do not use `ZoneId.of("UTC")`. Use a statically imported `UTC` from `ZoneOffset` instead (`import static java.time.ZoneOffset.UTC;`).
- **Millisecond Precision:** Always truncate `Instant.now()` to milliseconds (using `.truncatedTo(ChronoUnit.MILLIS)`) to maintain consistency with Joda `DateTime` and the PostgreSQL schema (which enforces millisecond precision via JPA converters).
- **Clock Injection:**
    - Avoid direct calls to `Instant.now()`, `DateTime.now()`, `ZonedDateTime.now()`, or `System.currentTimeMillis()`.
    - Inject `google.registry.util.Clock` (production) or `google.registry.testing.FakeClock` (tests).
    - Use `clock.nowDate()` to get a `ZonedDateTime` in UTC.
    - When defining timestamps for tests, prefer using a fixed, static constant (e.g., `Instant.parse("2024-03-27T10:15:30.105Z")`) over capturing `clock.now()` to prevent flaky tests caused by the passage of real time. Avoid using the Unix epoch (`START_INSTANT`) unless specifically testing epoch-related logic; instead, use realistic dates and vary them across different test suites to ensure logic isn't dependent on a specific "standard" date.
- **Beam Pipelines:**
    - Ensure `Clock` is serializable (it is by default in this project) when used in Beam `DoFn`s.
    - Pass the `Clock` through the constructor or via Dagger provider methods in the pipeline module.
- **Command-Line Tools:**
    - Use `@Inject Clock clock;` in `Command` implementations.
    - The `clock` field should be **package-private** (no access modifier) to allow manual initialization in corresponding test classes.
    - In test classes (e.g., `UpdateDomainCommandTest`), manually set `command.clock = fakeClock;` in the `@BeforeEach` method.
    - Base test classes like `EppToolCommandTestCase` should handle this assignment for their generic command types where applicable.

### 3. Dependency Injection (Dagger)
- **Concrete Types:** Dagger `inject` methods must use explicit concrete types. Generic `inject(Command)` methods will not work.
- **Test Components:** Use `TestRegistryToolComponent` for command-line tool tests to bridge the gap between `main` and `nonprod/test` source sets.

### 4. Database Consistency
- **JPA Converters:** Be aware that JPA converters (like `DateTimeConverter`) may perform truncation or transformation. Ensure application-level logic matches these transformations to avoid "dirty" state or unexpected diffs.
- **Transaction Management:**
    - **Top-Level:** Define database transactions (`tm().transact(...)`) at the highest possible level in the call chain (e.g., in an Action, a Command, or a Flow). This ensures all operations are atomic and handled by the retry logic.
    - **DAO Methods:** Avoid declaring transactions inside low-level DAO methods. Use `tm().assertInTransaction()` to ensure that these methods are only called within a valid transactional context.
    - **Utility/Cache Methods:** Use `tm().reTransact(...)` for utility methods or Caffeine cache loaders that might be invoked from both transactional and non-transactional paths.
        - `reTransact` will join an existing transaction if one is present (acting as a no-op) or start a new one if not.
        - This is particularly useful for in-memory caches where the loader must be able to fetch data regardless of whether the caller is currently in a transaction.
        - **Test Helpers & Timestamps:** If a static test helper method (like in `DatabaseHelper`) needs the database transaction time but might be called from outside a transaction, using `tm().reTransact(tm()::getTxTime)` is acceptable. However, NEVER wrap it redundantly like `tm().transact(() -> tm().reTransact(tm()::getTxTime))`. If you are just setting an arbitrary timestamp in a test where the exact DB transaction time isn't strictly required, prefer `Instant.now()` or `clock.now()` to avoid creating unnecessary database transactions.
        - **Production Code:** In production code, if a flow fails because it is calling `getTxTime()` outside of a transaction, you must wrap the *caller* in a transaction instead of adding an unnecessary `reTransact()` around `getTxTime()`.
    - **Transactional Time:** Ensure code that relies on `tm().getTransactionTime()` (or `tm().getTxTime()`) is executed within a transaction context.

### 5. Testing Best Practices
- **FakeClock and Sleeper:** Use `FakeClock` and `Sleeper` for any logic involving timeouts, delays, or expiration.
- **Empirical Reproduction:** Before fixing a bug, always create a test case that reproduces the failure.
- **Base Classes:** Leverage `CommandTestCase`, `EppToolCommandTestCase`, etc., to reduce boilerplate and ensure consistent setup (e.g., clock initialization).
- **Gradle Test Patterns:** When running tests to investigate fixes in the "core" directory, try to first use the "standardTest" Gradle task. It is faster than the "test" task, which includes the "fragileTest" task. Only run the full "test" task after "standardTest" succeeds.

### 6. Project Dependencies
- **Common Module:** When using `Clock` or other core utilities in a new or separate module (like `load-testing`), ensure `implementation project(':common')` is added to the module's `build.gradle`.

### 7. Search and Discovery
- **No CodeSearch:** This project is hosted on GitHub, not Google3. Do NOT use `mcp_Coding_search_for_files_codesearch` or other internal Google3 search tools.
- **Local Grep:** Use local shell commands like `git grep` or `grep` via `run_shell_command` to search the codebase.

## Performance and Efficiency
- **Turn Minimization:** Aim for "perfect" code in the first iteration. Iterative fixes for checkstyle or compilation errors consume significant context and time.
- **Context Management:** Use sub-agents for batch refactoring or high-volume output tasks to keep the main session history lean and efficient.
- **Code Formatting:** Do not write custom Python scripts or manual regex replacements to fix code formatting issues (e.g., unused imports, import ordering, line length). Instead, use the project's built-in formatting tools: run `./gradlew spotlessApply` to fix unused/unordered imports and `./gradlew javaIncrementalFormatApply` (or `google-java-format --replace <files>`) to automatically fix Java formatting and indentation errors.

## General Code Review Lessons & Avoidable Mistakes
Based on historical PR reviews, avoid the following common mistakes:
- **No Unnecessary Casts:** Do not unnecessarily cast objects if the method signature accepts the type directly (e.g., avoid `(Instant) fakeClock.now()` or `(ImmutableSet<String>) bsaQuery(...)` if it compiles without it).
- **Visibility Modifiers:** Do not use `/* package */` comments to denote package-private visibility. Just leave the modifier blank; it is an established idiom in this codebase.

### Advanced Java & Guava Idioms
- **Immutable Types:** Declare variables, fields, and return types explicitly as Guava immutable types (e.g., `ImmutableList<T>`, `ImmutableMap<K, V>`) instead of their generic interfaces (`List<T>`, `Map<K, V>`) to clearly communicate immutability contracts to callers. Use `toImmutableList()` and `toImmutableMap()` collectors in streams rather than manually accumulating into an `ArrayList` or `HashMap`.
- **Constructors:** Do not perform heavy logic, I/O, or external API calls inside constructors. Initialization logic should be deferred or handled in a factory method or a dedicated startup routine.
- **Exception Handling:** Do not catch generic `Exception` or `Throwable` if a more specific exception is expected. Never "log and re-throw" the same exception; either handle it entirely (and log), or throw it up the chain. For batch processes, catch exceptions at the individual item/chunk level so one failure doesn't abort the entire batch.
- **Optional Assertions:** Prefer Truth's `.hasValue(...)` over `.isEqualTo(Optional.of(...))` for cleaner and more descriptive assertions on `Optional` types.
- **Fail Fast:** Validate inputs and fail fast (using `Preconditions.checkArgument` or similar) at the highest level possible rather than passing invalid state (like `null`s) deeper into business logic.
- **Magic Numbers:** Always document magic numbers or hardcoded limits (like `50.0` or `30`) with inline comments explaining the rationale.
- **Null Safety and Optional:** Prefer using `Optional` for any variable that is expected to potentially be null. For any other variable that can be null but cannot use an `Optional` (e.g., function parameters or return types where `Optional` is not idiomatic), it MUST be annotated with `@Nullable`. Always use the `javax.annotation.Nullable` annotation.

---

# Gemini Engineering Guide: Nomulus Codebase

This document captures high-level architectural patterns, lessons learned from large-scale refactorings (like the Joda-Time to `java.time` migration), and specific instructions to avoid common pitfalls in this environment.

## 🏛 Architecture Overview

- **Transaction Management:** The codebase uses a custom wrapper around JPA. Always use `tm()` (from `TransactionManagerFactory`) to interact with the database.
- **Dependency Injection:** Dagger 2 is used extensively. If you see "cannot find symbol" errors for classes starting with `Dagger...`, the project is in a state where annotation processing failed. Fix compilation in core models first to restore generated code.
- **Value Types:** AutoValue and "ImmutableObject" patterns are dominant. Most models follow a `Buildable` pattern with a nested `Builder`.
- **Temporal Logic:** The project is migrating from Joda-Time to `java.time`.
  - Core boundaries: `DateTimeUtils.START_INSTANT` (Unix Epoch) and `DateTimeUtils.END_INSTANT` (Long.MAX_VALUE / 1000).
  - Year Arithmetic: Use `DateTimeUtils.plusYears()` and `DateTimeUtils.minusYears()` to handle February 29th logic correctly.

## Source Control
- **Committing:** Always create a new commit on the branch if one hasn't been created yet for the branch's specific work. Only perform amending (`git commit --amend --no-edit`) for subsequent changes once the initial commit has been successfully created.
- **One Commit Per PR:** All changes for a single PR must be squashed into a single commit before merging.
- **Default to Amend:** Once an initial commit is created for a PR, all subsequent functional changes should be amended into that same commit by default (`git commit --amend --no-edit`). This ensures the PR remains a single, clean unit of work throughout the development lifecycle.
- **Commit Message Style:** Follow standard Git commit best practices. The subject line (first line) MUST be a maximum of 50 characters, concise, capitalized, and **must not end with punctuation** (e.g., a period). The body MUST explicitly encapsulate and summarize all changes made across the entire diff, detailing the "what" and "why" comprehensively.
- **Strict Completion Verification:** You MUST NEVER declare a task, commit, or amendment as complete until you have explicitly verified that the workspace is clean. You MUST follow this exact sequence of actions across multiple conversational turns if necessary:
  1. Execute the `git commit` or `git commit --amend` command.
  2. Wait for the tool to return successfully.
  3. Execute `git status`.
  4. Wait for the tool to return and explicitly verify the output contains `nothing to commit, working tree clean` (or similar indication that no unstaged changes remain). If changes remain, stage them and amend the commit, then repeat this verification loop.
  5. **Only after** step 4 has successfully returned a clean working directory may you generate a text response to the user declaring that the task is complete.
- **Diff Review:** Before finalizing a task, review the full diff (e.g., `git diff HEAD^`) to ensure all changes are functional and relevant. Identify and revert any formatting-only changes in files that do not contain functional updates to keep the commit focused.

## Self-Review Guidelines
Before finalizing any PR or declaring a task complete, you MUST perform a thorough, rigorous self-review of your entire diff. Run `git diff HEAD^` (or review the staged changes) and actively verify the following against every modified line:

1. **Imports & FQNs:** Did I leave any fully-qualified class names or static variables inline? Did I add the necessary imports for them? *Crucial Exception:* If the file already imports a class with the identical name (e.g., it uses both `java.time.Duration` and `org.joda.time.Duration`), one MUST remain fully qualified to avoid a compilation conflict.
2. **Redundant Conversions:** Did I use `toDateTime(clock.now())` where `clock.nowUtc()` would suffice? Did I use `toDateTime(END_INSTANT)` instead of `END_OF_TIME`? Did I use `.toInstant()` or `.toDateTime()` on something that could be avoided by using a different method overload (e.g., `tm().getTxTime()`)?
3. **Verbose Math:** Did I write any verbose time conversions inline? Are there `DateTimeUtils` methods I should be using instead? If not, should I abstract this math into `DateTimeUtils`?
4. **Assertion Cleanliness:** Am I polluting test assertions with `toDateTime(...)` wraps? If so, I need to add overloaded assertions to the Truth Subjects instead.
5. **Diff Scope:** Are there any formatting-only changes in files that I did not functionally modify? If so, revert them. Does the total line count of the diff align with the approved scope (e.g., ~1,000 lines for migrations)?
6. **Commit Message:** Does the commit message title fit within 50 characters? Does the body encapsulate the entirety of the changes across the diff cleanly and professionally?
7. **Missing Tests & Coverage:** *Perform a structured check for any new methods or modified behavior.* Did I add a new utility method (like `plusMonths(Instant, int)`) or change core logic? If so, I MUST open the corresponding test file and write tests to cover the new functionality (including edge cases, negative values, and leap years) before considering the task complete. A code review is not thorough if it only checks for compilation. I must actively ensure every new branch of logic has a test.
8. **Package Lock:** Did I include `console-webapp/package-lock.json` in my diff? If so, I MUST revert it (`git checkout console-webapp/package-lock.json`) unless I explicitly intended to modify NPM dependencies. This file is often modified by the build process and should not be committed accidentally.

Only after actively confirming these checks against your diff are you permitted to finalize the task.

## Refactoring & Migration Guardrails


### 1. Compiler Warnings are Errors (`-Werror`)
This project treats Error Prone warnings as errors.
- **`@InlineMeSuggester`**: When creating deprecated Joda-Time bridge methods (e.g., `getTimestamp() -> return toDateTime(getTimestampInstant())`), you **MUST** immediately add `@SuppressWarnings("InlineMeSuggester")`. If you don't, the build will fail.
- **Repeatable Annotations**: `@SuppressWarnings` is **NOT** repeatable in this environment. If a method or class already has a suppression (e.g., `@SuppressWarnings("unchecked")`), you must merge them:
  - ❌ `@SuppressWarnings("unchecked") @SuppressWarnings("InlineMeSuggester")`
  - ✅ `@SuppressWarnings({"unchecked", "InlineMeSuggester"})`

### 2. Resolving Ambiguity
- **Null Overloads**: Adding an `Instant` overload to a method that previously took `DateTime` will break all `create(null)` calls. You must cast them: `create((Instant) null)`.
- **Type Erasure**: Methods taking `Optional<DateTime>` and `Optional<Instant>` will clash due to erasure. Use distinct names, e.g., `setAutorenewEndTimeInstant(Optional<Instant> time)`.

### 3. Build Strategy
- **Surgical Changes**: In large-scale migrations, focus on "leaf" nodes first (Utilities -> Models -> Flows -> Actions).
- **PR Size**: Minimize PR size by retaining Joda-Time bridge methods for high-level "Action" and "Flow" classes unless a full migration is requested. Reverting changes to DNS and Reporting logic while updating the underlying models is a valid strategy to keep PRs reviewable.
- **Validation**: Always run `./gradlew build -x test` before attempting to run unit tests. Unit tests will not run if there are compilation errors in any part of the `core` module. Before finalizing a PR or declaring a task done, you MUST verify your changes. **Prefer scoped builds** (e.g., `./gradlew :core:build`) if you are only modifying backend Java code. Running the global `./gradlew build` triggers the frontend `console-webapp` build, which unnecessarily runs `npmInstallDeps` and modifies `package-lock.json`. If you must run a global build, you must revert `console-webapp/package-lock.json` afterwards. Do not declare success if formatting checks (e.g., `spotlessCheck` or `javaIncrementalFormatCheck`) or tests fail. If formatting fails, run `./gradlew spotlessApply` and then re-run your build command to verify everything passes.

## 🚫 Common Pitfalls to Avoid

- **Mixing Joda and Java Durations:** Methods like `Tld.get().getRenewGracePeriodLength()` return a **Joda** `Duration`, which cannot be passed directly to `Instant.plus(...)` because it doesn't implement `TemporalAmount`. You MUST use `.plusMillis(duration.getMillis())` instead.
- **Serialization Precision (`.000Z`):** When asserting against or generating XML/YAML files, remember that millisecond precision (`.000Z`) is required. Always use `DateTimeUtils.formatInstant(...)` to format `Instant` objects (it preserves the `.000Z` suffix) instead of `Instant.toString()` (which drops it for exact seconds). We have added custom Jackson `InstantKeySerializer`s for this purpose, but you must keep this precision in mind when manually updating `.xml` or `.yaml` test data.
- **Static Imports:** Methods like `toDateTime`, `toInstant`, `plusYears`, `plusMonths`, and `minusDays` from `DateTimeUtils` MUST be statically imported. Do NOT use them fully qualified (e.g., `DateTimeUtils.plusMonths(...)`).

- **Redundant Parses:** Never write `toDateTime(Instant.parse(...))` or `toInstant(DateTime.parse(...))`. If you need a `DateTime`, use `DateTime.parse(...)` directly. If you need an `Instant`, use `Instant.parse(...)` directly.
- **cloneProjectedAtTime vs cloneProjectedAtTime:** When converting tests and logic that use `clock.now()` to project resource state into the future or past, do not wrap the Java `Instant` in `toDateTime()` just to call `cloneProjectedAtTime()`. Instead, switch the method call to use the native `cloneProjectedAtTime()` method which is available on all `EppResource` models.
- **Do not go in circles with the build:** If you see an `InlineMeSuggester` error, apply the suppression to **ALL** similar methods in that file and related files in one turn. Do not fix them one by one. Furthermore, do not run a global `./gradlew build` when a scoped `./gradlew :core:build` or `./gradlew :core:test` is faster and more appropriate. Run global builds only when doing final verification.
- **Exception Conversion in Tests:** When migrating time types (e.g., from Joda `DateTime` to Java `Instant`), be extremely careful with tests that verify parsing failures (e.g., `assertThrows(IllegalArgumentException.class, ...)`). Joda's `DateTime.parse()` throws an `IllegalArgumentException` on failure, but `Instant.parse()` throws a `java.time.format.DateTimeParseException`. You must update the expected exception type in these tests to ensure they actually test the correct behavior, and verify the tests are not failing prematurely on the first line if it contains invalid data meant to be ignored.
- Dagger/AutoValue corruption: If you modify a builder or a component incorrectly, Dagger will fail to generate code, leading to hundreds of "cannot find symbol" errors. If this happens, `git checkout` the last working state of the specific file and re-apply changes more surgically.
- **`replace` tool context**: When using `replace` on large files (like `Tld.java` or `DomainBase.java`), provide significant surrounding context. These files have many similar method signatures (getters/setters) that can lead to incorrect replacements.

---

## GitHub and Pull Request Protocol

This protocol defines the standard for interacting with GitHub repositories and processing Pull Request (PR) feedback.

### 1. Interaction via `gh` CLI
- **Primary Tool:** ALWAYS use the `gh` CLI for all GitHub-related operations (listing PRs, viewing PR content, checking status, adding comments).
- **Credential Safety:** Never expose tokens or credentials in shell commands.

### 2. Processing PR Feedback
- **Systematic Review:** When asked to address PR comments, first fetch all comments using `gh pr view <number> --json reviews,comments`.
- **Minimal Scope Expansion:** Address comments surgically. If a fix requires changes beyond a few lines or expands the PR's original scope significantly, DO NOT implement it without explicit user approval. Instead, report the issue to the user.
- **Verification:** After addressing feedback, run the full build (`./gradlew build`) and relevant tests to ensure no regressions were introduced.

### 3. PR Lifecycle Management
- **One Commit Per PR:** Ensure all changes are squashed into a single, clean commit. Use `git commit --amend --no-edit` for follow-up fixes.
- **Clean Workspace:** Always run `git status` and verify the repository state before declaring a task complete.
- **Package Lock:** The Gradle build automatically modifies `console-webapp/package-lock.json` via the `npmInstallDeps` task. ALWAYS revert this file (`git checkout console-webapp/package-lock.json`) before staging changes or finalizing a commit unless you explicitly modified NPM dependencies.
