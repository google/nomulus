# Engineering Standards for Gemini CLI

This document outlines foundational mandates, architectural patterns, and project-specific conventions to ensure high-quality, idiomatic, and consistent code from the first iteration.

## Core Mandates

### 1. Rigorous Import Management
- **Addition:** When adding new symbols, ensure the corresponding import is added.
- **Removal:** When removing the last usage of a class or symbol from a file (e.g., removing a `@Inject Clock clock;` field), **immediately remove the associated import**. Do not wait for a build failure to identify unused imports.
- **Checkstyle:** Proactively fix common checkstyle errors (line length > 100, formatting, unused imports) during the initial code write. Do not wait for CI/build failures to address these, as iterative fixes are inefficient.
- **Verification:** Before finalizing any change, scan the imports section for redundancy.

### 2. Time and Precision Handling (java.time Migration)
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
