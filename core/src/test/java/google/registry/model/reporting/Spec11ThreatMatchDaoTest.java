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
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestSqlOnly;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link Spec11ThreatMatchDao}. */
@DualDatabaseTest
public class Spec11ThreatMatchDaoTest extends EntityTestCase {

  private static final LocalDate TODAY = new LocalDate(2020, 8, 4);
  private static final LocalDate YESTERDAY = new LocalDate(2020, 8, 3);

  private DomainBase todayComDomain;
  private DomainBase todayOrgDomain;
  private DomainBase yesterdayComDomain;
  private DomainBase yesterdayOrgDomain;

  @BeforeEach
  void setUp() {
    createTlds("com", "org");
    ContactResource contact = persistActiveContact("jd1234");
    todayComDomain = persistResource(newDomainBase("today.com", contact));
    todayOrgDomain = persistResource(newDomainBase("today.org", contact));
    yesterdayComDomain = persistResource(newDomainBase("yesterday.com", contact));
    yesterdayOrgDomain = persistResource(newDomainBase("yesterday.org", contact));
    jpaTm()
        .transact(
            () -> {
              jpaTm().insertAll(getThreatMatchesToday());
              jpaTm().insertAll(getThreatMatchesYesterday());
            });
  }

  @TestSqlOnly
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

  @TestSqlOnly
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
