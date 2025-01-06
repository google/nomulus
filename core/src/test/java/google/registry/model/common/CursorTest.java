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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.model.tld.Tld;
import google.registry.util.SerializeUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Cursor}. */
public class CursorTest extends EntityTestCase {

  public CursorTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    fakeClock.setTo(DateTime.parse("2010-10-17TZ"));
  }

  @Test
  void testSerializable() {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    tm().transact(() -> tm().put(Cursor.createGlobal(RECURRING_BILLING, time)));
    Cursor persisted =
        tm().transact(() -> tm().loadByKey(Cursor.createGlobalVKey(RECURRING_BILLING)));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testSuccess_persistScopedCursor() {
    Tld tld = createTld("tld");
    this.fakeClock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    Cursor cursor = Cursor.createScoped(RDE_UPLOAD, time, tld);
    tm().transact(() -> tm().put(cursor));
    tm().transact(
            () -> {
              assertThat(tm().loadByKeyIfPresent(Cursor.createScopedVKey(BRDA, tld)).isPresent())
                  .isFalse();
              assertThat(tm().loadByKey(Cursor.createScopedVKey(RDE_UPLOAD, tld)).getCursorTime())
                  .isEqualTo(time);
            });
  }

  @Test
  void testSuccess_persistGlobalCursor() {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    Cursor cursor = Cursor.createGlobal(RECURRING_BILLING, time);
    tm().transact(() -> tm().put(cursor));
    assertThat(tm().transact(() -> tm().loadByKey(cursor.createVKey())).getCursorTime())
        .isEqualTo(time);
  }

  @Test
  void testFailure_VKeyWrongScope() {
    Tld tld = createTld("tld");
    assertThrows(
        IllegalArgumentException.class,
        () -> Cursor.createGlobalVKey(RDE_UPLOAD),
        "Scope GLOBAL does not match cursor type RDE_UPLOAD");
    assertThrows(
        IllegalArgumentException.class,
        () -> Cursor.createScopedVKey(RECURRING_BILLING, tld),
        "Scope tld does not match cursor type RECURRING_BILLING");
    assertThrows(
        NullPointerException.class, () -> Cursor.createScopedVKey(RECURRING_BILLING, null));
  }

  @Test
  void testFailure_nullScope() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.createScoped(RECURRING_BILLING, START_OF_TIME, null));
    assertThat(thrown).hasMessageThat().contains("Cursor scope cannot be null");
  }

  @Test
  void testFailure_nullCursorType() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.createScoped(null, START_OF_TIME, Tld.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor type cannot be null");
  }

  @Test
  void testFailure_nullTime() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.createScoped(RDE_UPLOAD, null, Tld.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor time cannot be null");
  }
}
