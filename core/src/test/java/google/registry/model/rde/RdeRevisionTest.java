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

package google.registry.model.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeRevision.getNextRevision;
import static google.registry.model.rde.RdeRevision.saveRevision;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.testing.DualDatabaseTest;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

/** Unit tests for {@link RdeRevision}. */
@DualDatabaseTest
public class RdeRevisionTest extends EntityTestCase {

  public RdeRevisionTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("1984-12-18TZ"));
  }

  @TestTemplate
  void testGetNextRevision_objectDoesntExist_returnsZero() {
    tm().transact(
            () -> assertThat(getNextRevision("torment", fakeClock.nowUtc(), FULL)).isEqualTo(0));
  }

  @TestTemplate
  void testGetNextRevision_objectExistsAtZero_returnsOne() {
    save("sorrow", fakeClock.nowUtc(), FULL, 0);
    tm().transact(
            () -> assertThat(getNextRevision("sorrow", fakeClock.nowUtc(), FULL)).isEqualTo(1));
  }

  @TestTemplate
  void testSaveRevision_objectDoesntExist_newRevisionIsZero_nextRevIsOne() {
    tm().transact(() -> saveRevision("despondency", fakeClock.nowUtc(), FULL, 0));
    tm().transact(
            () ->
                assertThat(getNextRevision("despondency", fakeClock.nowUtc(), FULL)).isEqualTo(1));
  }

  @TestTemplate
  void testSaveRevision_objectDoesntExist_newRevisionIsOne_throwsVe() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transact(() -> saveRevision("despondency", fakeClock.nowUtc(), FULL, 1)));
    assertThat(thrown).hasMessageThat().contains("object missing");
  }

  @TestTemplate
  void testSaveRevision_objectExistsAtZero_newRevisionIsZero_throwsVe() {
    save("melancholy", fakeClock.nowUtc(), FULL, 0);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transact(() -> saveRevision("melancholy", fakeClock.nowUtc(), FULL, 0)));
    assertThat(thrown).hasMessageThat().contains("object already created");
  }

  @TestTemplate
  void testSaveRevision_objectExistsAtZero_newRevisionIsOne_nextRevIsTwo() {
    DateTime startOfDay = fakeClock.nowUtc().withTimeAtStartOfDay();
    save("melancholy", startOfDay, FULL, 0);
    fakeClock.advanceOneMilli();
    tm().transact(() -> saveRevision("melancholy", startOfDay, FULL, 1));
    tm().transact(() -> assertThat(getNextRevision("melancholy", startOfDay, FULL)).isEqualTo(2));
  }

  @TestTemplate
  void testSaveRevision_objectExistsAtZero_newRevisionIsTwo_throwsVe() {
    save("melancholy", fakeClock.nowUtc(), FULL, 0);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transact(() -> saveRevision("melancholy", fakeClock.nowUtc(), FULL, 2)));
    assertThat(thrown).hasMessageThat().contains("should be at 1 ");
  }

  @TestTemplate
  void testSaveRevision_negativeRevision_throwsIae() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transact(() -> saveRevision("melancholy", fakeClock.nowUtc(), FULL, -1)));
    assertThat(thrown).hasMessageThat().contains("Negative revision");
  }

  @TestTemplate
  void testSaveRevision_callerNotInTransaction_throwsIse() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> saveRevision("frenzy", fakeClock.nowUtc(), FULL, 1));
    assertThat(thrown).hasMessageThat().contains("transaction");
  }

  public static void save(String tld, DateTime date, RdeMode mode, int revision) {
    String triplet = RdeNamingUtils.makePartialName(tld, date, mode);
    RdeRevision object = RdeRevision.create(triplet, tld, date.toLocalDate(), mode, revision);
    tm().transact(() -> tm().put(object));
  }
}
