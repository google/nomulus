// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EscrowTaskRunner}. */
public class EscrowTaskRunnerTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final EscrowTask task = mock(EscrowTask.class);
  private final FakeClock clock = new FakeClock(Instant.parse("2000-01-01T00:00:00Z"));

  private ZoneId previousDateTimeZone;
  private EscrowTaskRunner runner;
  private Tld registry;

  @BeforeEach
  void beforeEach() {
    createTld("lol");
    registry = Tld.get("lol");
    runner = new EscrowTaskRunner();
    runner.clock = clock;
    runner.lockHandler = new FakeLockHandler(true);
    previousDateTimeZone = ZoneId.systemDefault();
    // java.time.ZoneId does not have a global setDefault
    System.setProperty("user.timezone", "America/New_York"); // Make sure UTC stuff works.
  }

  @AfterEach
  void afterEach() {
    // Restore timezone
    System.setProperty("user.timezone", previousDateTimeZone.getId());
  }

  @Test
  void testRun_cursorIsToday_advancesCursorToTomorrow() throws Exception {
    clock.setTo(Instant.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.RDE_STAGING, Instant.parse("2006-06-06T00:00:00Z"), registry));
    runner.lockRunAndRollForward(
        task, registry, Duration.ofSeconds(30), CursorType.RDE_STAGING, Duration.ofDays(1));
    verify(task).runWithLock(Instant.parse("2006-06-06T00:00:00Z"));
    Cursor cursor = loadByKey(Cursor.createScopedVKey(CursorType.RDE_STAGING, registry));
    assertThat(cursor.getCursorTime()).isEqualTo(Instant.parse("2006-06-07T00:00:00Z"));
  }

  @Test
  void testRun_cursorMissing_assumesTodayAndAdvancesCursorToTomorrow() throws Exception {
    clock.setTo(Instant.parse("2006-06-06T00:30:00Z"));
    runner.lockRunAndRollForward(
        task, registry, Duration.ofSeconds(30), CursorType.RDE_STAGING, Duration.ofDays(1));
    verify(task).runWithLock(Instant.parse("2006-06-06T00:00:00Z"));
    Cursor cursor = loadByKey(Cursor.createScopedVKey(CursorType.RDE_STAGING, registry));
    assertThat(cursor.getCursorTime()).isEqualTo(Instant.parse("2006-06-07T00:00:00Z"));
  }

  @Test
  void testRun_cursorInTheFuture_doesNothing() {
    clock.setTo(Instant.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.RDE_STAGING, Instant.parse("2006-06-07T00:00:00Z"), registry));
    NoContentException thrown =
        assertThrows(
            NoContentException.class,
            () ->
                runner.lockRunAndRollForward(
                    task,
                    registry,
                    Duration.ofSeconds(30),
                    CursorType.RDE_STAGING,
                    Duration.ofDays(1)));
    assertThat(thrown).hasMessageThat().contains("Already completed");
  }

  @Test
  void testRun_lockIsntAvailable_throws503() {
    String lockName = "EscrowTaskRunner " + task.getClass().getSimpleName();
    clock.setTo(Instant.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.RDE_STAGING, Instant.parse("2006-06-06T00:00:00Z"), registry));
    runner.lockHandler = new FakeLockHandler(false);
    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () ->
                runner.lockRunAndRollForward(
                    task,
                    registry,
                    Duration.ofSeconds(30),
                    CursorType.RDE_STAGING,
                    Duration.ofDays(1)));
    assertThat(thrown).hasMessageThat().contains("Lock in use: " + lockName + " for TLD: lol");
  }
}
