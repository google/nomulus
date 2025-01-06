// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.sql.flyway;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Checks if new Flyway scripts may cause Database deadlock.
 *
 * <p>Flyway deploys each DDL script in one transaction. If a script modifies multiple schema
 * elements (table, index, sequence), it MAY hit deadlocks when applied on sandbox/production where
 * it'll be competing against live traffic that may also be locking said elements but in a different
 * order. This test checks every new script to be merged, counts the elements it locks, and raises
 * an error when there is more than one locked element.
 *
 * <p>For deadlock-prevention purpose, we can ignore elements being created or dropped: our
 * schema-server compatibility tests ensure that such elements are not accessed by live traffic.
 * Therefore, we focus on 'alter' statements. However, 'create index' is a special case: if the
 * 'concurrently' modifier is not present, the indexed table is locked.
 */
public class FlywayDeadlockTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Relative path (from the root of the repository) of the scripts directory.
  private static final String FLYWAY_SCRIPTS_DIR = "db/src/main/resources/sql/flyway";

  // For splitting a shell command into an array of strings.
  private static final Splitter SHELL_COMMAND_SPLITTER = Splitter.on(' ').trimResults();

  // For splitting a multi-statement SQL string into individual statements.
  private static final Splitter SQL_TEXT_SPLITTER =
      Splitter.on(";").trimResults().omitEmptyStrings();

  // For splitting the multi-line text containing all new script paths.
  private static final Splitter CHANGED_FILENAMES_SPLITTER =
      Splitter.on('\n').trimResults().omitEmptyStrings();

  // Command that returns the git repo's root dir when executed anywhere in the repo.
  private static final String GIT_GET_ROOT_DIR_CMD = "git rev-parse --show-toplevel";

  // Returns the commit hash when the current branch branches of the main branch.
  private static final String GIT_FORK_POINT_CMD = "git merge-base origin/master HEAD";

  // Command template to get changed Flyways scripts, with fork-point to be filled in. This command
  // is executed at the root dir of the repo. Any path returned is relative to the root dir.
  private static final String GET_CHANGED_SCRIPTS_CMD =
      "git diff %s --name-only " + FLYWAY_SCRIPTS_DIR;

  // Map of DDL patterns and the capture group index of the element name in it.
  public static final ImmutableMap<Pattern, Integer> DDL_PATTERNS =
      ImmutableMap.of(
          Pattern.compile(
              "^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+"
                  + "(IF\\s+NOT\\s+EXISTS\\s+)*(public.)?((\\w+)|(\"\\w+\"))\\s+ON\\s+(ONLY\\s+)?"
                  + "(public.)?((\\w+)|(\"\\w+\"))[^;]+$",
              CASE_INSENSITIVE),
          9,
          Pattern.compile(
              "^\\s*ALTER\\s+INDEX\\s+(IF\\s+EXISTS\\s+)?(public.)?((\\w+)|(\"\\w+\"))[^;]+$",
              CASE_INSENSITIVE),
          3,
          Pattern.compile(
              "^\\s*ALTER\\s+SEQUENCE\\s+(IF\\s+EXISTS\\s+)?(public.)?((\\w+)|(\"\\w+\"))[^;]+$",
              CASE_INSENSITIVE),
          3,
          Pattern.compile(
              "^\\s*ALTER\\s+TABLE\\s+(IF\\s+EXISTS\\s+|ONLY\\s+)*(public.)?((\\w+)|(\"\\w+\"))[^;]+$",
              CASE_INSENSITIVE),
          3);

  /**
   * Validates that new flyway scripts (if exist) do not have deadlock-inducing patterns.
   *
   * <p>This test may break if not invoked in a cloned Nomulus repository, therefore it is disabled
   * by default. User can enable it by setting the {@code do_flyway_deadlock_check} system property
   * with arbitrary non-empty value.
   */
  @Test
  @EnabledIfSystemProperty(
      named = "do_flyway_deadlock_check",
      matches = ".+",
      disabledReason = "Not compatible with the release process on Cloud Build.")
  public void validateNewFlywayScripts() {
    ImmutableList<Path> newScriptPaths = findChangedFlywayScripts();
    ImmutableList<String> scriptAndLockedElements =
        newScriptPaths.stream()
            .filter(path -> parseDdlScript(path).size() > 1)
            .map(path -> String.format("%s: %s", path, parseDdlScript(path)))
            .collect(toImmutableList());

    assertWithMessage("Scripts changing more than one schema elements:")
        .that(scriptAndLockedElements)
        .isEmpty();
  }

  @Test
  public void testGetDdlLockedElementName_found() {
    ImmutableList<String> ddls =
        ImmutableList.of(
            "alter table element_name ...",
            "ALTER table if EXISTS ONLY \"element_name\" ...",
            "alter index element_name \n...",
            "Alter sequence public.\"element_name\" ...",
            "create index if not exists \"index_name\" on element_name ...",
            "create index if not exists \"index_name\" on public.\"element_name\" ...",
            "create index if not exists index_name on public.element_name ...",
            "create index if not exists \"index_name\" on public.element_name ...",
            "create unique index public.index_name on public.\"element_name\" ...");
    ddls.forEach(ddl -> assertThat(getDdlLockedElementName(ddl)).hasValue("element_name"));
  }

  @Test
  public void testGetDdlLockedElementName_notFound() {
    ImmutableList<String> ddls =
        ImmutableList.of(
            "create table element_name ...;",
            "create sequence public.\"element_name\" ...;",
            "create index concurrently if not exists index_name on public.element_name ...;",
            "create unique index concurrently public.index_name on public.\"element_name\" ...;",
            "drop table element_name ...;",
            "drop sequence element_name ...;",
            "drop INDEX element_name ...;");
    ddls.forEach(ddl -> assertThat(getDdlLockedElementName(ddl)).isEmpty());
  }

  static Optional<String> getDdlLockedElementName(String ddl) {
    for (Map.Entry<Pattern, Integer> patternEntry : DDL_PATTERNS.entrySet()) {
      Matcher matcher = patternEntry.getKey().matcher(ddl);
      if (matcher.find()) {
        String name = matcher.group(patternEntry.getValue());
        return Optional.of(name.replace("\"", ""));
      }
    }
    return Optional.empty();
  }

  static ImmutableSet<String> parseDdlScript(Path path) {
    try {
      return SQL_TEXT_SPLITTER
          .splitToStream(
              readAllLines(path, UTF_8).stream()
                  .map(line -> line.replaceAll("--.*", ""))
                  .filter(line -> !line.isBlank())
                  .collect(joining(" ")))
          .map(FlywayDeadlockTest::getDdlLockedElementName)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(toImmutableSet());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static ImmutableList<Path> findChangedFlywayScripts() {
    try {
      String forkPoint;
      try {
        forkPoint = executeShellCommand(GIT_FORK_POINT_CMD);
      } catch (RuntimeException e) {
        if (e.getMessage() != null && e.getMessage().contains("not a git repository")) {
          logger.atInfo().log("Not in git repo: probably the internal-pr or -ci test.");
          return ImmutableList.of();
        }
        throw e;
      }
      String rootDir = executeShellCommand(GIT_GET_ROOT_DIR_CMD);
      String changedScriptsCommand = String.format(GET_CHANGED_SCRIPTS_CMD, forkPoint);
      ImmutableList<Path> changedPaths =
          CHANGED_FILENAMES_SPLITTER
              .splitToList(executeShellCommand(changedScriptsCommand, Optional.of(rootDir)))
              .stream()
              .map(pathStr -> rootDir + File.separator + pathStr)
              .map(Path::of)
              .collect(toImmutableList());
      if (changedPaths.isEmpty()) {
        logger.atInfo().log("There are no schema changes.");
      } else {
        logger.atInfo().log("Found %s new Flyway scripts", changedPaths.size());
      }
      return changedPaths;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static String executeShellCommand(String command) throws IOException {
    return executeShellCommand(command, Optional.empty());
  }

  static String executeShellCommand(String command, Optional<String> workingDir)
      throws IOException {
    ProcessBuilder processBuilder =
        new ProcessBuilder(SHELL_COMMAND_SPLITTER.splitToList(command).toArray(new String[0]));
    workingDir.map(File::new).ifPresent(processBuilder::directory);
    Process process = processBuilder.start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    String error = new String(process.getErrorStream().readAllBytes(), UTF_8);
    try {
      process.waitFor(1, SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    if (process.exitValue() != 0) {
      throw new RuntimeException(error);
    }
    return output.trim();
  }
}
