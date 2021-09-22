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

package google.registry.tools.javascrap;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.sampleThreatMatches;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParser;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.tools.CommandTestCase;
import java.io.IOException;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;

/** Tests for {@link BackfillSpec11ThreatMatchesCommand}. */
@DualDatabaseTest
public class BackfillSpec11ThreatMatchesCommandTest
    extends CommandTestCase<BackfillSpec11ThreatMatchesCommand> {

  private static final LocalDate CURRENT_DATE = DateTime.parse("2020-11-22").toLocalDate();
  private final Spec11RegistrarThreatMatchesParser threatMatchesParser =
      mock(Spec11RegistrarThreatMatchesParser.class);

  private DomainBase domainA;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("com");
    domainA = persistActiveDomain("a.com");
    persistActiveDomain("b.com");
    persistActiveDomain("c.com");
    fakeClock.setTo(CURRENT_DATE.toDateTimeAtStartOfDay());
    command.threatMatchesParser = threatMatchesParser;
    command.clock = fakeClock;
    when(threatMatchesParser.getRegistrarThreatMatches(any(LocalDate.class)))
        .thenReturn(ImmutableSet.of());
  }

  @TestOfyAndSql
  void testSuccess_singleFile() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    assertInStdout("Backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 3 threats.");
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  @TestOfyAndSql
  void testSuccess_sameDomain_multipleDays() throws Exception {
    // If the same domains show up on multiple days, there should be multiple entries for them
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    when(threatMatchesParser.getRegistrarThreatMatches(LocalDate.parse("2019-01-01")))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    assertInStdout("Backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 6 threats.");
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> threatMatches =
                  jpaTm().loadAllOf(Spec11ThreatMatch.class);
              assertThat(threatMatches).hasSize(6);
              assertThat(
                      threatMatches.stream()
                          .map(Spec11ThreatMatch::getDomainName)
                          .collect(toImmutableSet()))
                  .containsExactly("a.com", "b.com", "c.com");
              assertThat(
                      threatMatches.stream()
                          .map(Spec11ThreatMatch::getCheckDate)
                          .collect(toImmutableSet()))
                  .containsExactly(CURRENT_DATE, LocalDate.parse("2019-01-01"));
            });
  }

  @TestOfyAndSql
  void testSuccess_empty() throws Exception {
    runCommandForced();
    assertInStdout("Backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 0 threats.");
  }

  @TestOfyAndSql
  void testSuccess_sameDayTwice() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    runCommandForced();
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  @TestOfyAndSql
  void testSuccess_threeDomainsForDomainName() throws Exception {
    // We should use the repo ID from the proper DomainBase object at the scan's point in time.
    // First, domain was created at START_OF_TIME and deleted one year ago
    DateTime now = fakeClock.nowUtc();
    domainA = persistResource(domainA.asBuilder().setDeletionTime(now.minusYears(1)).build());

    // Next, domain was created six months ago and deleted two months ago
    DomainBase secondSave =
        persistResource(
            newDomainBase("a.com")
                .asBuilder()
                .setCreationTimeForTest(now.minusMonths(6))
                .setDeletionTime(now.minusMonths(2))
                .build());

    // Lastly, domain was created one month ago and is still valid
    DomainBase thirdSave =
        persistResource(
            newDomainBase("a.com").asBuilder().setCreationTimeForTest(now.minusMonths(1)).build());

    // If the scan result was from three months ago, we should use the second save
    when(threatMatchesParser.getRegistrarThreatMatches(now.toLocalDate().minusMonths(3)))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    String threatMatchRepoId =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(Spec11ThreatMatch.class).stream()
                        .filter((match) -> match.getDomainName().equals("a.com"))
                        .findFirst()
                        .get()
                        .getDomainRepoId());
    assertThat(threatMatchRepoId).isNotEqualTo(domainA.getRepoId());
    assertThat(threatMatchRepoId).isEqualTo(secondSave.getRepoId());
    assertThat(threatMatchRepoId).isNotEqualTo(thirdSave.getRepoId());
  }

  @TestOfyAndSql
  void testSuccess_skipsExistingDatesWithoutOverwrite() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    Spec11ThreatMatch previous =
        new Spec11ThreatMatch.Builder()
            .setCheckDate(CURRENT_DATE)
            .setDomainName("previous.tld")
            .setDomainRepoId("1-DOMAIN")
            .setRegistrarId("TheRegistrar")
            .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
            .build();
    insertInDb(previous);

    runCommandForced();
    ImmutableList<Spec11ThreatMatch> threatMatches =
        jpaTm().transact(() -> jpaTm().loadAllOf(Spec11ThreatMatch.class));
    assertAboutImmutableObjects()
        .that(Iterables.getOnlyElement(threatMatches))
        .isEqualExceptFields(previous, "id");
  }

  @TestOfyAndSql
  void testSuccess_overwritesExistingDatesWhenSpecified() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    Spec11ThreatMatch previous =
        new Spec11ThreatMatch.Builder()
            .setCheckDate(CURRENT_DATE)
            .setDomainName("previous.tld")
            .setDomainRepoId("1-DOMAIN")
            .setRegistrarId("TheRegistrar")
            .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
            .build();
    insertInDb(previous);

    runCommandForced("--overwrite_existing_dates");
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  @TestOfyAndSql
  void testFailure_oneFileFails() throws Exception {
    // If there are any exceptions, we should fail loud and fast
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE.minusDays(1)))
        .thenThrow(new IOException("hi"));
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, this::runCommandForced);
    assertThat(runtimeException.getCause().getClass()).isEqualTo(IOException.class);
    assertThat(runtimeException).hasCauseThat().hasMessageThat().isEqualTo("hi");
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(Spec11ThreatMatch.class))).isEmpty();
  }

  @TestOfyAndSql
  void testFailure_noDomainForDomainName() throws Exception {
    deleteResource(domainA);
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    assertThat(assertThrows(IllegalStateException.class, this::runCommandForced))
        .hasMessageThat()
        .isEqualTo("Domain name a.com had no associated DomainBase objects.");
  }

  @TestOfyAndSql
  void testFailure_noDomainAtTimeOfScan() throws Exception {
    // If the domain existed at some point(s) in time but not the time of the scan, fail.
    // First, domain was created at START_OF_TIME and deleted one year ago
    DateTime now = fakeClock.nowUtc();
    domainA = persistResource(domainA.asBuilder().setDeletionTime(now.minusYears(1)).build());

    // Second, domain was created one month ago and is still valid
    persistResource(
        newDomainBase("a.com").asBuilder().setCreationTimeForTest(now.minusMonths(1)).build());

    // If we have a result for this domain from 3 months ago when it didn't exist, fail.
    when(threatMatchesParser.getRegistrarThreatMatches(now.toLocalDate().minusMonths(3)))
        .thenReturn(sampleThreatMatches());
    assertThat(assertThrows(IllegalStateException.class, this::runCommandForced))
        .hasMessageThat()
        .isEqualTo("Could not find a DomainBase valid for a.com on day 2020-08-22.");
  }

  private void verifyExactlyThreeEntriesInDbFromLastDay() {
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> threatMatches =
                  jpaTm().loadAllOf(Spec11ThreatMatch.class);
              assertThat(threatMatches)
                  .comparingElementsUsing(immutableObjectCorrespondence("id", "domainRepoId"))
                  .containsExactly(
                      expectedThreatMatch("TheRegistrar", "a.com"),
                      expectedThreatMatch("NewRegistrar", "b.com"),
                      expectedThreatMatch("NewRegistrar", "c.com"));
            });
  }

  private Spec11ThreatMatch expectedThreatMatch(String registrarId, String domainName) {
    return new Spec11ThreatMatch.Builder()
        .setDomainRepoId("ignored")
        .setDomainName(domainName)
        .setRegistrarId(registrarId)
        .setCheckDate(CURRENT_DATE)
        .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
        .build();
  }
}
