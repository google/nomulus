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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.io.Files;
import com.google.common.truth.Truth8;
import google.registry.model.registry.label.ReservedList;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.schema.tld.ReservedList.ReservedEntry;
import google.registry.schema.tld.ReservedListDao;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import javax.persistence.EntityManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Base class for common testing setup for create and update commands for Reserved Lists.
 *
 * @param <T> command type
 */
public abstract class CreateOrUpdateReservedListCommandTestCase
    <T extends CreateOrUpdateReservedListCommand> extends CommandTestCase<T> {

  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  String reservedTermsPath;
  String invalidReservedTermsPath;

  @Before
  public void init() throws IOException {
    File reservedTermsFile = tmpDir.newFile("xn--q9jyb4c_common-reserved.txt");
    File invalidReservedTermsFile = tmpDir.newFile("reserved-terms-wontparse.csv");
    String reservedTermsCsv =
        loadFile(CreateOrUpdateReservedListCommandTestCase.class, "example_reserved_terms.csv");
    Files.asCharSink(reservedTermsFile, UTF_8).write(reservedTermsCsv);
    Files.asCharSink(invalidReservedTermsFile, UTF_8)
        .write("sdfgagmsdgs,sdfgsd\nasdf234tafgs,asdfaw\n\n");
    reservedTermsPath = reservedTermsFile.getPath();
    invalidReservedTermsPath = invalidReservedTermsFile.getPath();
  }

  @Test
  public void testFailure_fileDoesntExist() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=xn--q9jyb4c-blah", "--input=" + reservedTermsPath + "-nonexistent"));
  }

  @Test
  public void testFailure_fileDoesntParse() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("--name=xn--q9jyb4c-blork", "--input=" + invalidReservedTermsPath));
  }

  google.registry.schema.tld.ReservedList createCloudSqlReservedList(
      String name, boolean shouldPublish, Map<String, ReservedEntry> labelsToEntries) {
    return google.registry.schema.tld.ReservedList.create(name, shouldPublish, labelsToEntries);
  }

  google.registry.schema.tld.ReservedList getCloudSqlReservedList(String name) {
    return jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              long revisionId =
                  em.createQuery(
                          "SELECT MAX(rl.revisionId) FROM ReservedList rl WHERE name = :name",
                          Long.class)
                      .setParameter("name", name)
                      .getSingleResult();
              return em.createQuery(
                      "FROM ReservedList rl LEFT JOIN FETCH rl.labelsToReservations WHERE"
                          + " rl.revisionId = :revisionId",
                      google.registry.schema.tld.ReservedList.class)
                  .setParameter("revisionId", revisionId)
                  .getSingleResult();
            });
  }

  void verifyXnq9jyb4cInCloudSql() {
    assertThat(ReservedListDao.checkExists("xn--q9jyb4c_common-reserved")).isTrue();
    google.registry.schema.tld.ReservedList persistedList =
        getCloudSqlReservedList("xn--q9jyb4c_common-reserved");
    assertThat(persistedList.getName()).isEqualTo("xn--q9jyb4c_common-reserved");
    assertThat(persistedList.getShouldPublish()).isTrue();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
    assertThat(persistedList.getLabelsToReservations())
        .containsExactly(
            "baddies",
            ReservedEntry.create(FULLY_BLOCKED, ""),
            "ford",
            ReservedEntry.create(FULLY_BLOCKED, "random comment"));
  }

  void verifyXnq9jyb4cInDatastore() {
    Truth8.assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
    Truth8.assertThat(reservedList.getReservationInList("baddies")).hasValue(FULLY_BLOCKED);
    Truth8.assertThat(reservedList.getReservationInList("ford")).hasValue(FULLY_BLOCKED);
  }
}
