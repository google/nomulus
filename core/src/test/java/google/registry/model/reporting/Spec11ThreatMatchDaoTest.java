// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Spec11ThreatMatchDao}. */
public class Spec11ThreatMatchDaoTest extends EntityTestCase {

  private static final LocalDate TODAY = new LocalDate(2020, 8, 4);
  private static final LocalDate YESTERDAY = new LocalDate(2020, 8, 3);

  @BeforeEach
  void setUp() {
    jpaTm()
        .transact(
            () -> {
              jpaTm().insertAll(getThreatMatchesToday());
              jpaTm().insertAll(getThreatMatchesYesterday());
            });
  }

  @Test
  void testDeleteEntriesByDate() {
    // Verify that all entries with the date TODAY were removed
    jpaTm()
        .transact(
            () -> {
              Spec11ThreatMatchDao.deleteEntriesByDate(jpaTm(), TODAY);
              ImmutableList<Spec11ThreatMatch> persistedToday =
                  Spec11ThreatMatchDao.loadEntriesByDate(jpaTm(), TODAY);
              assertThat(persistedToday).isEmpty();
            });

    // Verify that all other entries were not removed
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> persistedYesterday =
                  Spec11ThreatMatchDao.loadEntriesByDate(jpaTm(), YESTERDAY);
              assertThat(persistedYesterday)
                  .comparingElementsUsing(immutableObjectCorrespondence("id"))
                  .containsExactlyElementsIn(getThreatMatchesYesterday());
            });
  }

  @Test
  void testLoadEntriesByDate() {
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> persisted =
                  Spec11ThreatMatchDao.loadEntriesByDate(jpaTm(), TODAY);

              assertThat(persisted)
                  .comparingElementsUsing(immutableObjectCorrespondence("id"))
                  .containsExactlyElementsIn(getThreatMatchesToday());
            });
  }

  private ImmutableList<Spec11ThreatMatch> getThreatMatchesYesterday() {
    return ImmutableList.of(
        createThreatMatch("yesterday.com", YESTERDAY),
        createThreatMatch("yesterday.org", YESTERDAY));
  }

  private ImmutableList<Spec11ThreatMatch> getThreatMatchesToday() {
    return ImmutableList.of(
        createThreatMatch("today.com", TODAY), createThreatMatch("today.org", TODAY));
  }

  private Spec11ThreatMatch createThreatMatch(String domainName, LocalDate date) {
    return new Spec11ThreatMatch()
        .asBuilder()
        .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
        .setCheckDate(date)
        .setDomainName(domainName)
        .setRegistrarId("Example Registrar")
        .setDomainRepoId("1-COM")
        .build();
  }
}
