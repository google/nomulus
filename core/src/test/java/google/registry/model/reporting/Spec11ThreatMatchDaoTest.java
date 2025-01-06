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

package google.registry.model.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.testing.DatabaseHelper;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Spec11ThreatMatchDao}. */
class Spec11ThreatMatchDaoTest extends EntityTestCase {

  private static final LocalDate TODAY = new LocalDate(2020, 8, 4);
  private static final LocalDate YESTERDAY = new LocalDate(2020, 8, 3);

  private Domain todayComDomain;
  private Domain todayOrgDomain;
  private Domain yesterdayComDomain;
  private Domain yesterdayOrgDomain;

  @BeforeEach
  void setUp() {
    createTlds("com", "org");
    Contact contact = persistActiveContact("jd1234");
    todayComDomain = persistResource(DatabaseHelper.newDomain("today.com", contact));
    todayOrgDomain = persistResource(DatabaseHelper.newDomain("today.org", contact));
    yesterdayComDomain = persistResource(DatabaseHelper.newDomain("yesterday.com", contact));
    yesterdayOrgDomain = persistResource(DatabaseHelper.newDomain("yesterday.org", contact));
    tm().transact(
            () -> {
              tm().insertAll(getThreatMatchesToday());
              tm().insertAll(getThreatMatchesYesterday());
            });
  }

  @Test
  void testDeleteEntriesByDate() {
    // Verify that all entries with the date TODAY were removed
    tm().transact(
            () -> {
              Spec11ThreatMatchDao.deleteEntriesByDate(tm(), TODAY);
              ImmutableList<Spec11ThreatMatch> persistedToday =
                  Spec11ThreatMatchDao.loadEntriesByDate(tm(), TODAY);
              assertThat(persistedToday).isEmpty();
            });

    // Verify that all other entries were not removed
    tm().transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> persistedYesterday =
                  Spec11ThreatMatchDao.loadEntriesByDate(tm(), YESTERDAY);
              assertThat(persistedYesterday)
                  .comparingElementsUsing(immutableObjectCorrespondence("id"))
                  .containsExactlyElementsIn(getThreatMatchesYesterday());
            });
  }

  @Test
  void testLoadEntriesByDate() {
    tm().transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> persisted =
                  Spec11ThreatMatchDao.loadEntriesByDate(tm(), TODAY);

              assertThat(persisted)
                  .comparingElementsUsing(immutableObjectCorrespondence("id"))
                  .containsExactlyElementsIn(getThreatMatchesToday());
            });
  }

  private ImmutableList<Spec11ThreatMatch> getThreatMatchesYesterday() {
    return ImmutableList.of(
        createThreatMatch("yesterday.com", yesterdayComDomain.getRepoId(), YESTERDAY),
        createThreatMatch("yesterday.org", yesterdayOrgDomain.getRepoId(), YESTERDAY));
  }

  private ImmutableList<Spec11ThreatMatch> getThreatMatchesToday() {
    return ImmutableList.of(
        createThreatMatch("today.com", todayComDomain.getRepoId(), TODAY),
        createThreatMatch("today.org", todayOrgDomain.getRepoId(), TODAY));
  }

  private Spec11ThreatMatch createThreatMatch(
      String domainName, String domainRepoId, LocalDate date) {
    return new Spec11ThreatMatch()
        .asBuilder()
        .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
        .setCheckDate(date)
        .setDomainName(domainName)
        .setRegistrarId("TheRegistrar")
        .setDomainRepoId(domainRepoId)
        .build();
  }
}
