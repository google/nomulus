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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EscrowTaskRunner}. */
public class EscrowTaskRunnerTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private final EscrowTask task = mock(EscrowTask.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private DateTimeZone previousDateTimeZone;
  private EscrowTaskRunner runner;
  private Registry registry;

  @BeforeEach
  void beforeEach() {
    createTld("lol");
    registry = Registry.get("lol");
    runner = new EscrowTaskRunner();
    runner.clock = clock;
    runner.lockHandler = new FakeLockHandler(true);
    previousDateTimeZone = DateTimeZone.getDefault();
    DateTimeZone.setDefault(DateTimeZone.forID("America/New_York"));  // Make sure UTC stuff works.
  }

  @AfterEach
  void afterEach() {
    DateTimeZone.setDefault(previousDateTimeZone);
  }

  @Test
  void testRun_cursorIsToday_advancesCursorToTomorrow() throws Exception {
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, DateTime.parse("2006-06-06TZ"), registry));
    runner.lockRunAndRollForward(
        task, registry, standardSeconds(30), CursorType.RDE_STAGING, standardDays(1));
    verify(task).runWithLock(DateTime.parse("2006-06-06TZ"));
    ofy().clearSessionCache();
    Cursor cursor = ofy().load().key(Cursor.createKey(CursorType.RDE_STAGING, registry)).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-06-07TZ"));
  }

  @Test
  void testRun_cursorMissing_assumesTodayAndAdvancesCursorToTomorrow() throws Exception {
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    runner.lockRunAndRollForward(
        task, registry, standardSeconds(30), CursorType.RDE_STAGING, standardDays(1));
    verify(task).runWithLock(DateTime.parse("2006-06-06TZ"));
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.RDE_STAGING, Registry.get("lol"))).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-06-07TZ"));
  }

  @Test
  void testRun_cursorInTheFuture_doesNothing() {
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, DateTime.parse("2006-06-07TZ"), registry));
    NoContentException thrown =
        assertThrows(
            NoContentException.class,
            () ->
                runner.lockRunAndRollForward(
                    task, registry, standardSeconds(30), CursorType.RDE_STAGING, standardDays(1)));
    assertThat(thrown).hasMessageThat().contains("Already completed");
  }

  @Test
  void testRun_lockIsntAvailable_throws503() {
    String lockName = "EscrowTaskRunner " + task.getClass().getSimpleName();
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, DateTime.parse("2006-06-06TZ"), registry));
    runner.lockHandler = new FakeLockHandler(false);
    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () ->
                runner.lockRunAndRollForward(
                    task, registry, standardSeconds(30), CursorType.RDE_STAGING, standardDays(1)));
    assertThat(thrown).hasMessageThat().contains("Lock in use: " + lockName + " for TLD: lol");
  }
}
