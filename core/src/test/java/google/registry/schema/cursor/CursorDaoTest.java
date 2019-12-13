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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.testing.TestLogHandler;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Cursor}. */
@RunWith(JUnit4.class)
public class CursorDaoTest {

  private FakeClock fakeClock = new FakeClock();

  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept = Logger.getLogger(CursorDao.class.getCanonicalName());

  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void save_worksSuccessfullyOnNewCursor() {
    Cursor cursor = Cursor.create(CursorType.BRDA, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.BRDA, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnExistingCursor() {
    Cursor cursor = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnNewGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnExistingGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 =
        Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  public void load_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.save(cursor);
    CursorDao.save(cursor2);
    CursorDao.save(cursor3);
    CursorDao.save(cursor4);
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.BRDA, "foo");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor4.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void loadAll_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.save(cursor);
    CursorDao.save(cursor2);
    CursorDao.save(cursor3);
    CursorDao.save(cursor4);
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(4);
  }

  @Test
  public void loadAll_worksSuccessfullyEmptyTable() {
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  public void loadByType_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.save(cursor);
    CursorDao.save(cursor2);
    CursorDao.save(cursor3);
    CursorDao.save(cursor4);
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(2);
  }

  @Test
  public void loadByType_worksSuccessfullyNoneOfType() {
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  public void saveCursor_worksSuccessfully() {
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
  public void saveCursor_worksSuccessfullyOnGlobalCursor() {
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
  public void saveCursor_logsInfoWhenSaveToCloudSqlFails() {
    loggerToIntercept.addHandler(logHandler);
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.saveCursor(cursor, null);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Issue saving cursor to CloudSql: Scope cannot be null. To create a global cursor, use"
                + " the createGlobal method");
    google.registry.model.common.Cursor dataStoreCursor =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(cursor).isEqualTo(dataStoreCursor);
  }
}
