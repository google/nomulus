# Nomulus Engineering Guide for Gemini

Core mandates, architectural patterns, and conventions for Nomulus. Review the whole file when editing to keep it coherent and non-redundant.

## Architecture Overview
- **Transaction Management:** Custom JPA wrapper; always use `tm()` (from `TransactionManagerFactory`).
- **Dependency Injection:** Dagger 2. `"cannot find symbol"` for `Dagger...` means annotation processing failed; fix core model compilation first (or `git checkout` a corrupted builder/component and re-apply surgically).
- **Value Types:** AutoValue and `ImmutableObject` (`Buildable` + nested `Builder`).
- **Temporal Logic:** `java.time` with `DateTimeUtils.START_INSTANT` (Unix Epoch) and `DateTimeUtils.END_INSTANT` (`Long.MAX_VALUE / 1000`).

## Core Mandates

### 1. Imports and Formatting
- **Addition / Removal:** Add the import for every new symbol. When removing the last usage of a symbol (e.g., an `@Inject Clock clock;` field), **immediately remove its import**.
- **No Redundant Qualifications:** NEVER use fully qualified class names (e.g., `java.time.temporal.ChronoUnit.DAYS`) when an import can be used. *Exception:* if the file already imports a class with the same simple name (e.g., `java.util.Date` and `java.sql.Date`), one must remain fully qualified.
- **Static Imports for Utilities:** Statically import methods from utility classes like `DateTimeUtils` or `CacheUtils` (e.g., `toInstant(...)`, not `DateTimeUtils.toInstant(...)`).
- **Checkstyle:** Proactively avoid common checkstyle errors (line length > 100, formatting, unused imports) when first writing code.
- **Formatting Tools:** Never hand-roll scripts or regexes to fix formatting. Run `./gradlew spotlessApply` (imports) and `./gradlew javaIncrementalFormatApply` (or `google-java-format --replace <files>`) for Java formatting.
- **License Headers:** New files use the current year (e.g., 2026). Existing files retain their original year.

### 2. Time and Precision
- **UTC:** Never use `ZoneId.of("UTC")`; use statically imported `ZoneOffset.UTC`.
- **Clock Injection:** Avoid `Instant.now()`, `OffsetDateTime.now()`, and `System.currentTimeMillis()`. Inject `google.registry.util.Clock` (production) or `google.registry.testing.FakeClock` (tests). Use `clock.nowDate()` for a UTC `LocalDate` and `clock.nowDateTime()` for a UTC `OffsetDateTime`.
- **Test Timestamps:** Prefer fixed constants (e.g., `Instant.parse("2024-03-27T10:15:30.105Z")`) over capturing `clock.now()` to avoid flakiness.
- **Beam Pipelines:** `Clock` is serializable; pass it through the `DoFn` constructor or Dagger provider methods in the pipeline module.
- **Command-Line Tools:** Use a package-private `@Inject Clock clock;` field in `Command` implementations and set `command.clock = fakeClock;` in the test's `@BeforeEach` (base classes like `EppToolCommandTestCase` do this for generic command types).
- **Serialization Precision (`.000Z`):** Format `Instant`s with statically imported `formatInstant(...)` from `DateTimeUtils`, never `Instant.toString()` (which drops `.000Z` for whole seconds). Keep this in mind when editing `.xml`/`.yaml` test data.

### 3. Dependency Injection (Dagger)
- **Concrete Types:** Dagger `inject` methods must use explicit concrete types; generic `inject(Command)` methods do not work.
- **Test Components:** Use `TestRegistryToolComponent` for command-line tool tests to bridge `main` and `nonprod/test` source sets.

### 4. Database Consistency
- **Top-Level Transactions:** Define `tm().transact(...)` at the highest level of the call chain (Action, Command, or Flow) so all work is atomic and covered by retries.
- **DAO Methods:** Never open transactions inside low-level DAO methods; use `tm().assertInTransaction()`.
- **`reTransact`:** Use `tm().reTransact(...)` for utility methods or Caffeine cache loaders reachable from both transactional and non-transactional paths (it joins an existing transaction or starts a new one). Test helpers may use `tm().reTransact(tm()::getTxTime)`, but NEVER `tm().transact(() -> tm().reTransact(...))`; when exact DB time is not needed, use a fixed static `Instant.parse(...)` constant. In production code, wrap the *caller* in a transaction instead of adding `reTransact()` around `getTxTime()`.
- **`tm()` vs. `replicaTm()`:** Transaction state lives in a static `ThreadLocal` shared by every `JpaTransactionManagerImpl`, so a `replicaTm()` call inside a `tm()` transaction (or vice versa) is a nested transaction on the *outer* database. Finish one before starting the other.
- **2-PR Schema Split:** Read `db/README.md` before any schema change. Never combine Flyway scripts (`db/src/main/resources/sql/flyway/V*.sql`) with Java ORM changes in one PR, because servers mid-rollout fail on unmigrated columns:
    1. **PR #1 (Schema Only):** the Flyway script, `flyway.txt` (`:db:generateFlywayIndex`), `nomulus.golden.sql` (`:nom:generate_golden_file`), and ER diagrams. **Zero `.java` or `db-schema.sql.generated` changes.**
    2. **PR #2 (ORM):** after PR #1 is deployed to production: entity/flow changes, tests, and the regenerated `db-schema.sql.generated` (`generateSqlSchema`).

### 5. Java, Guava, and Nomulus Idioms
- **Compiler Warnings are Errors (`-Werror`):** `@SuppressWarnings` is not repeatable; merge suppressions: `@SuppressWarnings({"unchecked", "MustBeClosedChecker"})`.
- **Immutable Types:** Declare fields, variables, and return types as Guava immutable types (`ImmutableList<T>`, `ImmutableMap<K, V>`) and collect with `toImmutableList()` / `toImmutableMap()` rather than accumulating into `ArrayList` / `HashMap`.
- **Null Safety:** Prefer `Optional` for possibly-absent values; otherwise annotate with `javax.annotation.Nullable` (never another `Nullable`).
- **Fail Fast:** Validate inputs at the highest level (`Preconditions.checkArgument`) instead of passing invalid state deeper.
- **Exceptions:** Catch specific exceptions, never generic `Exception`/`Throwable` when a narrower type is expected. Never log and re-throw. In batch jobs, catch per item/chunk so one failure does not abort the batch.
- **Constructors:** No heavy logic, I/O, or external calls in constructors; use factory methods or startup routines.
- **Magic Numbers:** Document hardcoded limits with an inline comment explaining the rationale.
- **No Unnecessary Casts:** e.g., avoid `(Instant) fakeClock.now()` if it compiles without the cast.
- **Visibility:** Never write `/* package */`; leave the modifier blank for package-private.
- **Model Layering:** Do not add new `google.registry.model` -> `google.registry.flows` dependencies. Put shared helpers in `google.registry.util` (e.g., `DomainNameUtils`).
- **Nullable `ImmutableObject` Sets:** Back nullable `Set` fields with a getter returning `ImmutableSortedSet` (e.g., `nullToEmptyImmutableSortedCopy`) and annotate the field with `@GetterDelegate` so `toString`, diffs, and `ModelUtils.getFieldValues()` use the getter.
- **Partial JSON Updates:** With Gson-deserialized partial updates, an omitted field is `null` and must preserve the stored value, while an explicit `[]` clears it. Validate list contents fail-closed (trim entries, reject `null` elements, enforce formats and size limits).
- **JAXB Optional Attributes:** Optional XML attributes with a default (e.g., fee `standard` / `refundable`, driven by `FeeType`) are `Boolean` fields left `null` when equal to the default so JAXB omits them.
- **Allocation Tokens:** Check single-use semantics with `tokenType.isOneTimeUse()`, not comparisons against specific `TokenType` values.
- **Console Actions:** Derive EPP superuser execution from the action type after the permission check, never from `user.getUserRoles().isAdmin()`. Prefix `ConsoleUpdateHistory` descriptions with the registrar ID and `ConsoleUpdateHistory.DESCRIPTION_SEPARATOR` so registrar history queries find them.
- **CSV Output:** Build formats with `CSVFormat.DEFAULT.builder().setRecordSeparator('\n').get()` (`build()` is deprecated and the default separator is `\r\n`).
- **Kubernetes JVM Flags:** When changing `JDK_JAVA_OPTIONS`, update every container in every manifest under `jetty/kubernetes/` and `proxy/kubernetes/` (some deployments have multiple containers) so all pods and rollbacks stay in parity.
- **Node Version:** Keep the `node` block in the root `build.gradle` and the Node install in `release/builder/build.sh` on the same version.

## Testing
- **Mandatory Proactive Testing:** Write and update tests alongside every code change without being asked. New features, bug fixes, and logic changes require coverage in the corresponding `*Test.java` (including edge cases).
- **Empirical Reproduction:** Before fixing a bug, write a test that reproduces it.
- **FakeClock and Sleeper:** Use them for any timeout, delay, or expiration logic; never `Thread.sleep()`.
- **Base Classes:** Leverage `CommandTestCase`, `EppToolCommandTestCase`, etc., for consistent setup.
- **Truth:** Prefer `.hasValue(...)` over `.isEqualTo(Optional.of(...))`.
- **JVM Reuse:** `:core:standardTest` reuses JVMs across test classes, so static state and `System.setProperty` values leak between tests. Give static singletons a `resetForTesting()` hook, publish shared state through a single `volatile` field, and validate inputs before mutating global properties.
- **Gradle Test Tasks:** In `core`, run `./gradlew :core:standardTest -x :console-webapp:buildConsoleWebapp` first (faster than `test`, which adds `fragileTest`); run the full `test` task only after it passes.
- **SQL Integration:** Whenever a schema, Flyway script, or JPA entity changes, also run `./gradlew :db:test` and `./gradlew :core:sqlIntegrationTest`.

## Build Strategy
- **Compile Before Testing:** Run `./gradlew :core:build -x test -x :console-webapp:buildConsoleWebapp` for backend-only changes (pass `-x :console-webapp:buildConsoleWebapp` so `:core:processResources` does not trigger `npmInstallDeps`, dirtying `console-webapp/package-lock.json` or failing on host Node.js version mismatches). Run `./gradlew build -x test` when `console-webapp/` is modified.
- **Package Lock:** Revert `console-webapp/package-lock.json` (`git checkout console-webapp/package-lock.json`) before committing unless NPM dependencies intentionally changed.
- **Formatting Failures:** Never declare success if `spotlessCheck`, `javaIncrementalFormatCheck`, or tests fail. Run `./gradlew spotlessApply javaIncrementalFormatApply` and re-verify.

## Project Dependencies
- **Common Module:** Modules using `Clock` or other core utilities (e.g., `load-testing`) need `implementation project(':common')` in their `build.gradle`.
- **Updating Dependencies:** Follow this sequence exactly:
  1. Modify `dependencies.gradle`.
  2. Run `./gradlew dependencies --write-locks --update-locks '*:*'` to regenerate all Gradle lockfiles.
  3. Run `./gradlew clean build` against the new dependency tree. If APIs or output formats changed, **fix our codebase** to comply; only revert or cap the version if the required changes are prohibitively complex or out of scope.
  4. Commit `dependencies.gradle` and every modified lockfile. Run `git status` and confirm no modified or untracked `*.lockfile` (including `buildscript-gradle.lockfile`) remains.

## Search and Discovery
- This project is hosted on GitHub. Search it with local tools such as `git grep` or `grep`, not monorepo code search tools.

## Source Control and Pull Requests
- **Public Repository:** Never commit internal-only links, hostnames, file paths, or shortlinks. Bug-tracker references such as `b/123` are fine.
- **Fresh Base:** Fetch fresh `upstream/master` (or `origin/master`) before branching so diffs are never computed against a stale base.
- **One Commit Per PR:** Create one commit for the branch's work and amend follow-up fixes (`git commit --amend --no-edit`).
- **Commit Message Style:** Subject at most 50 characters, capitalized, no trailing punctuation. Open the body with 2–4 plain-English sentences stating **why** the change is being made, what problem, waste, or risk exists today, and **what concrete outcome improves**—before naming classes, flags, or implementation mechanics ("what"/"how"), including operational justification and CLI usage examples where relevant.
- **Completion Verification:** After every commit or amend, run `git status` and confirm `nothing to commit, working tree clean` before declaring work complete.
- **Diff Review:** Review the full branch diff before finalizing and revert formatting-only edits in unrelated files.
- **Default Push Gate:** Unless overridden by a user-level rule or an explicit user directive to open/update a PR, stop at the local commit (`git commit`), report the SHA and `git status`, and ask before running `git push`. Never chain `git push` (or any remote transfer) with `git commit` via `&&`, `||`, or `;`—run every remote transfer as a standalone command.
- **`gh` CLI:** Use `gh` for all GitHub operations. Never expose tokens or credentials in commands.
- **Processing PR Feedback:** Fetch ALL review comments with paginated `gh api --paginate` calls across review comments, issue comments, and reviews (see `.agents/skills/pr-polisher/SKILL.md`). Address feedback surgically (report large scope expansions first), then re-run scoped build/tests per Build Strategy.
- **PR Description Sync:** After pushing an amended commit, update the PR description (`gh pr edit`) if it mirrored the old commit message. Inspect `gh pr view --json body` first and preserve Reviewable footers (`<!-- Reviewable:start -->...<!-- Reviewable:end -->` and `This change is https://reviewable.io/reviews/...`). Never edit the remote PR description for local-only commits.

## Pre-Commit Verification
Before responding after any commit or amend, run the `pr-polisher` skill (`.agents/skills/pr-polisher/SKILL.md`): `python3 .agents/skills/pr-polisher/scripts/check_diff.py`, `./gradlew runPresubmits`, and scoped build/tests per Build Strategy. Fix errors, amend, and confirm `0` errors and a clean `git status`.

## Common Pitfalls & Efficiency
- **Large-File Edits:** Include generous surrounding context when editing files with many similar signatures (e.g., `Tld.java`, `DomainBase.java`).
- **Turn Minimization & Delegation:** Aim for checkstyle-clean code on the first pass; use sub-agents for batch refactoring or high-volume output.
- **Markdown Measurement:** Measure Markdown line lengths programmatically (`awk '{print length($0)}'`) rather than estimating.
- **Continuous Improvement:** When hitting recurring tool/script friction, false positives, or repeated corrections, proactively propose a targeted fix to `GEMINI.md`, `.agents/skills/`, or an automated check (asking before editing meta-files during an unrelated task).
