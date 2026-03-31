# Engineering Standards for Gemini CLI

This document outlines foundational mandates, architectural patterns, and project-specific conventions to ensure high-quality, idiomatic, and consistent code from the first iteration.

## Core Mandates

### 1. Rigorous Import Management
- **Addition:** When adding new symbols, ensure the corresponding import is added.
- **Removal:** When removing the last usage of a class or symbol from a file (e.g., removing a `@Inject Clock clock;` field), **immediately remove the associated import**. Do not wait for a build failure to identify unused imports.
- **Verification:** Before finalizing any change, scan the imports section for redundancy.

### 2. Time and Precision Handling (java.time Migration)
- **Millisecond Precision:** Always truncate `Instant.now()` to milliseconds (using `.truncatedTo(ChronoUnit.MILLIS)`) to maintain consistency with Joda `DateTime` and the PostgreSQL schema (which enforces millisecond precision via JPA converters).
- **Clock Injection:**
    - Avoid direct calls to `Instant.now()`, `DateTime.now()`, or `System.currentTimeMillis()`.
    - Inject `google.registry.util.Clock` (production) or `google.registry.testing.FakeClock` (tests).
- **Command-Line Tools:**
    - Use `@Inject Clock clock;` in `Command` implementations.
    - The `clock` field should be **package-private** (no access modifier) to allow manual initialization in corresponding test classes.
    - In test classes (e.g., `UpdateDomainCommandTest`), manually set `command.clock = fakeClock;` in the `@BeforeEach` method if it exists.
    - Base test classes like `EppToolCommandTestCase` should handle this assignment for their generic command types where applicable.

### 3. Dependency Injection (Dagger)
- **Concrete Types:** Dagger `inject` methods must use explicit concrete types. Generic `inject(Command)` methods will not work.
- **Test Components:** Use `TestRegistryToolComponent` for command-line tool tests to bridge the gap between `main` and `nonprod/test` source sets.

### 4. Database Consistency
- **JPA Converters:** Be aware that JPA converters (like `DateTimeConverter`) may perform truncation or transformation. Ensure application-level logic matches these transformations to avoid "dirty" state or unexpected diffs.
- **Transactions:** Ensure code that relies on `tm().getTransactionTime()` is executed within a transaction context.

### 5. Testing Best Practices
- **FakeClock and Sleeper:** Use `FakeClock` and `Sleeper` for any logic involving timeouts, delays, or expiration.
- **Empirical Reproduction:** Before fixing a bug, always create a test case that reproduces the failure.
- **Base Classes:** Leverage `CommandTestCase`, `EppToolCommandTestCase`, etc., to reduce boilerplate and ensure consistent setup (e.g., clock initialization).

## Performance and Efficiency
- **Turn Minimization:** Aim for "perfect" code in the first iteration. Iterative fixes for checkstyle or compilation errors consume significant context and time.
- **Context Management:** Use sub-agents for batch refactoring or high-volume output tasks to keep the main session history lean and efficient.
