// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.cursor;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.TestLogHandler;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Cursor}. */
public class CursorDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept = Logger.getLogger(CursorDao.class.getCanonicalName());

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .enableJpaEntityCoverageCheck(true)
          .withClock(fakeClock)
          .build();

  @Test
  void save_worksSuccessfullyOnNewCursor() {
    Cursor cursor = Cursor.create(CursorType.BRDA, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.BRDA, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  void save_worksSuccessfullyOnExistingCursor() {
    Cursor cursor = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  void save_worksSuccessfullyOnNewGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  void save_worksSuccessfullyOnExistingGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 =
        Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  void saveAll_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    ImmutableSet<Cursor> cursors = ImmutableSet.<Cursor>builder().add(cursor, cursor2).build();
    CursorDao.saveAll(cursors);
    assertThat(CursorDao.loadAll()).hasSize(2);
    assertThat(CursorDao.load(CursorType.RECURRING_BILLING).getCursorTime())
        .isEqualTo(cursor.getCursorTime());
  }

  @Test
  void saveAll_worksSuccessfullyEmptySet() {
    CursorDao.saveAll(ImmutableSet.of());
    assertThat(CursorDao.loadAll()).isEmpty();
  }

  @Test
  void load_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.BRDA, "foo");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor4.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  void loadAll_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(4);
  }

  @Test
  void loadAll_worksSuccessfullyEmptyTable() {
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  void loadByType_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(2);
  }

  @Test
  void loadByType_worksSuccessfullyNoneOfType() {
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  void saveCursor_worksSuccessfully() {
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.saveCursor(cursor, "tld");
    Cursor createdCursor = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "tld");
    google.registry.model.common.Cursor dataStoreCursor =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(createdCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
    assertThat(cursor).isEqualTo(dataStoreCursor);
  }

  @Test
  void saveCursor_worksSuccessfullyOnGlobalCursor() {
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.saveCursor(cursor, Cursor.GLOBAL);
    Cursor createdCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    google.registry.model.common.Cursor dataStoreCursor =
        ofy()
            .load()
            .key(google.registry.model.common.Cursor.createGlobalKey(CursorType.RECURRING_BILLING))
            .now();
    assertThat(createdCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
    assertThat(cursor).isEqualTo(dataStoreCursor);
  }

  @Test
  void saveCursors_worksSuccessfully() {
    createTlds("tld", "foo");
    google.registry.model.common.Cursor cursor1 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    google.registry.model.common.Cursor cursor2 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("foo"));
    google.registry.model.common.Cursor cursor3 =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    ImmutableMap<google.registry.model.common.Cursor, String> cursors =
        ImmutableMap.<google.registry.model.common.Cursor, String>builder()
            .put(cursor1, "tld")
            .put(cursor2, "foo")
            .put(cursor3, Cursor.GLOBAL)
            .build();
    CursorDao.saveCursors(cursors);
    Cursor createdCursor1 = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "tld");
    google.registry.model.common.Cursor dataStoreCursor1 =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(createdCursor1.getCursorTime()).isEqualTo(cursor1.getCursorTime());
    assertThat(cursor1).isEqualTo(dataStoreCursor1);
    Cursor createdCursor2 = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "foo");
    google.registry.model.common.Cursor dataStoreCursor2 =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("foo")))
            .now();
    assertThat(createdCursor2.getCursorTime()).isEqualTo(cursor2.getCursorTime());
    assertThat(cursor2).isEqualTo(dataStoreCursor2);
    Cursor createdCursor3 = CursorDao.load(CursorType.RECURRING_BILLING);
    google.registry.model.common.Cursor dataStoreCursor3 =
        ofy()
            .load()
            .key(google.registry.model.common.Cursor.createGlobalKey(CursorType.RECURRING_BILLING))
            .now();
    assertThat(createdCursor3.getCursorTime()).isEqualTo(cursor3.getCursorTime());
    assertThat(cursor3).isEqualTo(dataStoreCursor3);
  }

  @Test
  void loadAndCompare_worksSuccessfully() {
    loggerToIntercept.addHandler(logHandler);
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.saveCursor(cursor, "tld");
    CursorDao.loadAndCompare(cursor, "tld");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.WARNING);
  }

  @Test
  void loadAndCompare_worksSuccessfullyGlobalCursor() {
    loggerToIntercept.addHandler(logHandler);
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.saveCursor(cursor, Cursor.GLOBAL);
    CursorDao.loadAndCompare(cursor, Cursor.GLOBAL);
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.WARNING);
  }

  @Test
  void loadAndCompare_worksSuccessfullyCursorNotInCloudSql() {
    loggerToIntercept.addHandler(logHandler);
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.loadAndCompare(cursor, "tld");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "Cursor of type ICANN_UPLOAD_ACTIVITY with the scope tld was not found in Cloud SQL.");
  }

  @Test
  void loadAndCompare_worksSuccessfullyGlobalCursorNotInCloudSql() {
    loggerToIntercept.addHandler(logHandler);
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.loadAndCompare(cursor, Cursor.GLOBAL);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "Cursor of type RECURRING_BILLING with the scope GLOBAL was not found in Cloud SQL.");
  }

  @Test
  void loadAndCompare_worksSuccessfullyCursorsNotEqual() {
    loggerToIntercept.addHandler(logHandler);
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.saveCursor(cursor, "tld");
    cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc().minusDays(5), Registry.get("tld"));
    CursorDao.loadAndCompare(cursor, "tld");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "This cursor of type ICANN_UPLOAD_ACTIVITY with the scope tld has a cursorTime of"
                + " 1969-12-27T00:00:00.000Z in Datastore and 1970-01-01T00:00:00.000Z in Cloud"
                + " SQL.");
  }

  @Test
  void loadAndCompareAll_worksSuccessfully() {
    loggerToIntercept.addHandler(logHandler);

    // Create Datastore cursors
    createTlds("tld", "foo");
    google.registry.model.common.Cursor cursor1 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    google.registry.model.common.Cursor cursor2 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("foo"));

    // Save cursors to Cloud SQL
    ImmutableMap<google.registry.model.common.Cursor, String> cursors =
        ImmutableMap.<google.registry.model.common.Cursor, String>builder()
            .put(cursor1, "tld")
            .put(cursor2, "foo")
            .build();
    CursorDao.saveCursors(cursors);

    CursorDao.loadAndCompareAll(cursors, CursorType.ICANN_UPLOAD_ACTIVITY);
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.WARNING);
  }

  @Test
  void loadAndCompareAll_worksSuccessfullyMissingOne() {
    loggerToIntercept.addHandler(logHandler);

    // Create Datastore cursors
    createTlds("tld", "foo", "lol");
    google.registry.model.common.Cursor cursor1 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    google.registry.model.common.Cursor cursor2 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("foo"));

    // Save Cursors to Cloud SQL
    ImmutableMap.Builder<google.registry.model.common.Cursor, String> cursors =
        ImmutableMap.<google.registry.model.common.Cursor, String>builder()
            .put(cursor1, "tld")
            .put(cursor2, "foo");
    CursorDao.saveCursors(cursors.build());

    // Create a new Datastore cursor that is not saved to Cloud SQL
    google.registry.model.common.Cursor cursor3 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc().minusDays(4), Registry.get("lol"));

    // Call loadAndCompareAll with all three Datastore cursors
    CursorDao.loadAndCompareAll(
        cursors.put(cursor3, "lol").build(), CursorType.ICANN_UPLOAD_ACTIVITY);

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "Cursor of type ICANN_UPLOAD_ACTIVITY with the scope lol was not found in Cloud SQL.");
  }

  @Test
  void loadAndCompareAll_worksSuccessfullyOneWithWrongTime() {
    loggerToIntercept.addHandler(logHandler);

    // Create Datastore cursors
    createTlds("tld", "foo");
    google.registry.model.common.Cursor cursor1 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    google.registry.model.common.Cursor cursor2 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("foo"));

    // Save Cursors to Cloud SQL
    CursorDao.saveCursors(ImmutableMap.of(cursor1, "tld", cursor2, "foo"));

    // Change time of first Datastore cursor, but don't save new time to Cloud SQL
    google.registry.model.common.Cursor cursor3 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc().minusDays(4), Registry.get("tld"));

    CursorDao.loadAndCompareAll(
        ImmutableMap.of(cursor2, "foo", cursor3, "tld"), CursorType.ICANN_UPLOAD_ACTIVITY);

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "This cursor of type ICANN_UPLOAD_ACTIVITY with the scope tld has a cursorTime of"
                + " 1969-12-28T00:00:00.000Z in Datastore and 1970-01-01T00:00:00.000Z in Cloud"
                + " SQL.");
  }

  @Test
  void loadAndCompareAll_worksSuccessfullyEmptyMap() {
    loggerToIntercept.addHandler(logHandler);
    CursorDao.loadAndCompareAll(ImmutableMap.of(), CursorType.ICANN_UPLOAD_ACTIVITY);
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.WARNING);
  }
}
