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

import google.registry.model.common.Cursor.CursorType;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.testing.FakeClock;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Cursor}. */
@RunWith(JUnit4.class)
public class CursorDaoTest {

  private FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

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
}
