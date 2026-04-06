# Engineering Standards for Gemini CLI

This document outlines foundational mandates, architectural patterns, and project-specific conventions to ensure high-quality, idiomatic, and consistent code from the first iteration.

## Core Mandates

### 1. Rigorous Import Management
- **Addition:** When adding new symbols, ensure the corresponding import is added.
- **Removal:** When removing the last usage of a class or symbol from a file (e.g., removing a `@Inject Clock clock;` field), **immediately remove the associated import**. Do not wait for a build failure to identify unused imports.
- **Checkstyle:** Proactively fix common checkstyle errors (line length > 100, formatting, unused imports) during the initial code write. Do not wait for CI/build failures to address these, as iterative fixes are inefficient.
- **Verification**: Before finalizing any change, scan the imports section for redundancy.
- **License Headers**: When creating new files, ensure the license header uses the current year (e.g., 2026). Existing files should retain their original year.

## 2. Time and Precision Handling (java.time Migration)

- **Millisecond Precision:** Always truncate `Instant.now()` to milliseconds (using `.truncatedTo(ChronoUnit.MILLIS)`) to maintain consistency with Joda `DateTime` and the PostgreSQL schema (which enforces millisecond precision via JPA converters).
- **Clock Injection:**
    - Avoid direct calls to `Instant.now()`, `DateTime.now()`, `ZonedDateTime.now()`, or `System.currentTimeMillis()`.
    - Inject `google.registry.util.Clock` (production) or `google.registry.testing.FakeClock` (tests).
    - Use `clock.nowDate()` to get a `ZonedDateTime` in UTC.
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
    - **Transactional Time:** Ensure code that relies on `tm().getTransactionTime()` is executed within a transaction context.

### 5. Testing Best Practices
- **FakeClock and Sleeper:** Use `FakeClock` and `Sleeper` for any logic involving timeouts, delays, or expiration.
- **Empirical Reproduction:** Before fixing a bug, always create a test case that reproduces the failure.
- **Base Classes:** Leverage `CommandTestCase`, `EppToolCommandTestCase`, etc., to reduce boilerplate and ensure consistent setup (e.g., clock initialization).

### 6. Project Dependencies
- **Common Module:** When using `Clock` or other core utilities in a new or separate module (like `load-testing`), ensure `implementation project(':common')` is added to the module's `build.gradle`.

## Performance and Efficiency
- **Turn Minimization:** Aim for "perfect" code in the first iteration. Iterative fixes for checkstyle or compilation errors consume significant context and time.
- **Context Management:** Use sub-agents for batch refactoring or high-volume output tasks to keep the main session history lean and efficient.

---

# Gemini Engineering Guide: Nomulus Codebase

This document captures high-level architectural patterns, lessons learned from large-scale refactorings (like the Joda-Time to `java.time` migration), and specific instructions to avoid common pitfalls in this environment.

## 🏛 Architecture Overview

- **Transaction Management:** The codebase uses a custom wrapper around JPA. Always use `tm()` (from `TransactionManagerFactory`) to interact with the database.
- **Dependency Injection:** Dagger 2 is used extensively. If you see "cannot find symbol" errors for classes starting with `Dagger...`, the project is in a state where annotation processing failed. Fix compilation in core models first to restore generated code.
- **Value Types:** AutoValue and "ImmutableObject" patterns are dominant. Most models follow a `Buildable` pattern with a nested `Builder`.
- **Temporal Logic:** The project is migrating from Joda-Time to `java.time`.
  - Core boundaries: `DateTimeUtils.START_OF_TIME_INSTANT` (Unix Epoch) and `END_OF_TIME_INSTANT` (Long.MAX_VALUE / 1000).
  - Year Arithmetic: Use `DateTimeUtils.leapSafeAddYears()` and `leapSafeSubtractYears()` to handle February 29th logic correctly.

## Source Control
- **One Commit Per PR:** All changes for a single PR must be squashed into a single commit before merging. This maintains a clean, linear history.
- **Commit Message Style:** Follow standard Git commit best practices. The subject line (first line) should be concise, capitalized, and **must not end with punctuation** (e.g., a period).
- **Final Validation:** Always run `git status` as the final step before declaring a task complete to ensure all changes are committed and the working directory is clean.
- **Diff Review:** Before finalizing a task, review the full diff (e.g., `git diff HEAD^`) to ensure all changes are functional and relevant. Identify and revert any formatting-only changes in files that do not contain functional updates to keep the commit focused.

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
- **Validation**: Always run `./gradlew build -x test` before attempting to run unit tests. Unit tests will not run if there are compilation errors in any part of the `core` module. Before finalizing a PR, run the entire build using `./gradlew build` to ensure all checks (including tests, checkstyle, and presubmits) pass.

## 🚫 Common Pitfalls to Avoid

- **Do not go in circles with the build:** If you see an `InlineMeSuggester` error, apply the suppression to **ALL** similar methods in that file and related files in one turn. Do not fix them one by one.
- **Dagger/AutoValue corruption:** If you modify a builder or a component incorrectly, Dagger will fail to generate code, leading to hundreds of "cannot find symbol" errors. If this happens, `git checkout` the last working state of the specific file and re-apply changes more surgically.
- **`replace` tool context**: When using `replace` on large files (like `Tld.java` or `DomainBase.java`), provide significant surrounding context. These files have many similar method signatures (getters/setters) that can lead to incorrect replacements.
