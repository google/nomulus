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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeRevision.getNextRevision;
import static google.registry.model.rde.RdeRevision.saveRevision;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static org.junit.Assert.assertThrows;

import com.google.common.base.VerifyException;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeRevision}. */
@RunWith(JUnit4.class)
public class RdeRevisionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();
  @Test
  public void testGetNextRevision_objectDoesntExist_returnsZero() {
    assertThat(getNextRevision("torment", DateTime.parse("1984-12-18TZ"), FULL))
        .isEqualTo(0);
  }

  @Test
  public void testGetNextRevision_objectExistsAtZero_returnsOne() {
    save("sorrow", DateTime.parse("1984-12-18TZ"), FULL, 0);
    assertThat(getNextRevision("sorrow", DateTime.parse("1984-12-18TZ"), FULL))
        .isEqualTo(1);
  }

  @Test
  public void testSaveRevision_objectDoesntExist_newRevisionIsZero_nextRevIsOne() {
    tm().transact(() -> saveRevision("despondency", DateTime.parse("1984-12-18TZ"), FULL, 0));
    tm()
        .transact(
            () ->
                assertThat(getNextRevision("despondency", DateTime.parse("1984-12-18TZ"), FULL))
                    .isEqualTo(1));
  }

  @Test
  public void testSaveRevision_objectDoesntExist_newRevisionIsOne_throwsVe() {
    VerifyException thrown =
        assertThrows(
            VerifyException.class,
            () ->
                tm()
                    .transact(
                        () ->
                            saveRevision("despondency", DateTime.parse("1984-12-18TZ"), FULL, 1)));
    assertThat(thrown).hasMessageThat().contains("object missing");
  }

  @Test
  public void testSaveRevision_objectExistsAtZero_newRevisionIsZero_throwsVe() {
    save("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 0);
    VerifyException thrown =
        assertThrows(
            VerifyException.class,
            () ->
                tm()
                    .transact(
                        () -> saveRevision("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 0)));
    assertThat(thrown).hasMessageThat().contains("object already created");
  }

  @Test
  public void testSaveRevision_objectExistsAtZero_newRevisionIsOne_nextRevIsTwo() {
    save("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 0);
    tm().transact(() -> saveRevision("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 1));
    tm()
        .transact(
            () ->
                assertThat(getNextRevision("melancholy", DateTime.parse("1984-12-18TZ"), FULL))
                    .isEqualTo(2));
  }

  @Test
  public void testSaveRevision_objectExistsAtZero_newRevisionIsTwo_throwsVe() {
    save("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 0);
    VerifyException thrown =
        assertThrows(
            VerifyException.class,
            () ->
                tm()
                    .transact(
                        () -> saveRevision("melancholy", DateTime.parse("1984-12-18TZ"), FULL, 2)));
    assertThat(thrown).hasMessageThat().contains("should be at 1 ");
  }

  @Test
  public void testSaveRevision_negativeRevision_throwsIae() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tm()
                    .transact(
                        () ->
                            saveRevision("melancholy", DateTime.parse("1984-12-18TZ"), FULL, -1)));
    assertThat(thrown).hasMessageThat().contains("Negative revision");
  }

  @Test
  public void testSaveRevision_callerNotInTransaction_throwsIse() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> saveRevision("frenzy", DateTime.parse("1984-12-18TZ"), FULL, 1));
    assertThat(thrown).hasMessageThat().contains("transaction");
  }

  public static void save(String tld, DateTime date, RdeMode mode, int revision) {
    String triplet = RdeNamingUtils.makePartialName(tld, date, mode);
    RdeRevision object = new RdeRevision();
    object.id = triplet;
    object.revision = revision;
    ofy().saveWithoutBackup().entity(object).now();
  }
}
