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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableListMultimap.flatteningToImmutableListMultimap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.model.reporting.Spec11ThreatMatchDao;
import google.registry.persistence.transaction.QueryComposer;
import google.registry.reporting.spec11.RegistrarThreatMatches;
import google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParser;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.util.Clock;
import java.io.IOException;
import java.util.Comparator;
import java.util.function.Function;
import javax.inject.Inject;
import org.joda.time.LocalDate;

/**
 * Scrap tool to backfill {@link Spec11ThreatMatch} objects from prior days.
 *
 * <p>This will load the previously-existing Spec11 files from GCS (looking back to 2019-01-01 (a
 * rough estimate of when we started using this format) and convert those RegistrarThreatMatches
 * objects into the new Spec11ThreatMatch format. It will then insert these entries into SQL.
 *
 * <p>Note that the script will attempt to find the corresponding {@link DomainBase} object for each
 * domain name on the day of the scan. It will fail if it cannot find a corresponding domain object,
 * or if the domain objects were not active at the time of the scan.
 */
@Parameters(
    commandDescription =
        "Backfills Spec11 threat match entries from the old and deprecated GCS JSON files to the "
            + "Cloud SQL database.")
public class BackfillSpec11ThreatMatchesCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final LocalDate START_DATE = new LocalDate(2019, 1, 1);

  @Parameter(
      names = {"-o", "--overwrite_existing_dates"},
      description =
          "Whether the command will overwrite data that already exists for dates that exist in the "
              + "GCS bucket. Defaults to false.")
  private boolean overrideExistingDates;

  @Inject Spec11RegistrarThreatMatchesParser threatMatchesParser;
  // Inject the clock for testing purposes
  @Inject Clock clock;

  @Override
  protected String prompt() {
    return String.format("Backfill Spec11 results from %d files?", getDatesToBackfill().size());
  }

  @Override
  protected String execute() {
    ImmutableList<LocalDate> dates = getDatesToBackfill();
    ImmutableListMultimap.Builder<LocalDate, RegistrarThreatMatches> threatMatchesBuilder =
        new ImmutableListMultimap.Builder<>();
    for (LocalDate date : dates) {
      try {
        // It's OK if the file doesn't exist for a particular date; the result will be empty.
        threatMatchesBuilder.putAll(date, threatMatchesParser.getRegistrarThreatMatches(date));
      } catch (IOException e) {
        throw new RuntimeException(
            String.format("Error parsing through file with date %s.", date), e);
      }
    }
    ImmutableListMultimap<LocalDate, RegistrarThreatMatches> threatMatches =
        threatMatchesBuilder.build();
    // Look up all possible DomainBases for these domain names, any of which can be in the past
    ImmutableListMultimap<String, DomainBase> domainsByDomainName =
        getDomainsByDomainName(threatMatches);

    // For each date, convert all threat matches with the proper domain repo ID
    int totalNumThreats = 0;
    for (LocalDate date : threatMatches.keySet()) {
      ImmutableList.Builder<Spec11ThreatMatch> spec11ThreatsBuilder = new ImmutableList.Builder<>();
      for (RegistrarThreatMatches rtm : threatMatches.get(date)) {
        rtm.threatMatches().stream()
            .map(
                threatMatch ->
                    threatMatchToCloudSqlObject(
                        threatMatch, date, rtm.clientId(), domainsByDomainName))
            .forEach(spec11ThreatsBuilder::add);
      }
      ImmutableList<Spec11ThreatMatch> spec11Threats = spec11ThreatsBuilder.build();
      jpaTm()
          .transact(
              () -> {
                Spec11ThreatMatchDao.deleteEntriesByDate(jpaTm(), date);
                jpaTm().putAll(spec11Threats);
              });
      totalNumThreats += spec11Threats.size();
    }
    return String.format(
        "Successfully parsed through %d files with %d threats.", dates.size(), totalNumThreats);
  }

  /** Returns a per-domain list of possible DomainBase objects, starting with the most recent. */
  private ImmutableListMultimap<String, DomainBase> getDomainsByDomainName(
      ImmutableListMultimap<LocalDate, RegistrarThreatMatches> threatMatchesByDate) {
    return threatMatchesByDate.values().stream()
        .map(RegistrarThreatMatches::threatMatches)
        .flatMap(ImmutableList::stream)
        .map(ThreatMatch::fullyQualifiedDomainName)
        .distinct()
        .collect(
            flatteningToImmutableListMultimap(
                Function.identity(),
                (domainName) -> {
                  ImmutableList<DomainBase> domains = loadDomainsForFqdn(domainName);
                  checkState(
                      !domains.isEmpty(),
                      "Domain name %s had no associated DomainBase objects.",
                      domainName);
                  return domains.stream()
                      .sorted(Comparator.comparing(DomainBase::getCreationTime).reversed());
                }));
  }

  /** Loads in all {@link DomainBase} objects for a given FQDN. */
  private ImmutableList<DomainBase> loadDomainsForFqdn(String fullyQualifiedDomainName) {
    return transactIfJpaTm(
        () ->
            tm().createQueryComposer(DomainBase.class)
                .where(
                    "fullyQualifiedDomainName",
                    QueryComposer.Comparator.EQ,
                    fullyQualifiedDomainName)
                .list());
  }

  /** Converts the previous {@link ThreatMatch} object to {@link Spec11ThreatMatch}. */
  private Spec11ThreatMatch threatMatchToCloudSqlObject(
      ThreatMatch threatMatch,
      LocalDate date,
      String registrarId,
      ImmutableListMultimap<String, DomainBase> domainsByDomainName) {
    DomainBase domain =
        findDomainAsOfDateOrThrow(
            threatMatch.fullyQualifiedDomainName(), date, domainsByDomainName);
    return new Spec11ThreatMatch.Builder()
        .setThreatTypes(ImmutableSet.of(ThreatType.valueOf(threatMatch.threatType())))
        .setCheckDate(date)
        .setRegistrarId(registrarId)
        .setDomainName(threatMatch.fullyQualifiedDomainName())
        .setDomainRepoId(domain.getRepoId())
        .build();
  }

  /** Returns the DomainBase object as of the particular date, which is likely in the past. */
  private DomainBase findDomainAsOfDateOrThrow(
      String domainName,
      LocalDate date,
      ImmutableListMultimap<String, DomainBase> domainsByDomainName) {
    ImmutableList<DomainBase> domains = domainsByDomainName.get(domainName);
    for (DomainBase domain : domains) {
      // We only know the date (not datetime) of the threat scan, so we approximate
      LocalDate creationDate = domain.getCreationTime().toLocalDate();
      LocalDate deletionDate = domain.getDeletionTime().toLocalDate();
      if (!date.isBefore(creationDate) && !date.isAfter(deletionDate)) {
        return domain;
      }
    }
    throw new IllegalStateException(
        String.format("Could not find a DomainBase valid for %s on day %s.", domainName, date));
  }

  /** Returns the list of dates between {@link #START_DATE} and now (UTC), inclusive. */
  private ImmutableList<LocalDate> getDatesToBackfill() {
    ImmutableSet<LocalDate> datesToSkip =
        overrideExistingDates ? ImmutableSet.of() : getExistingDates();
    ImmutableList.Builder<LocalDate> result = new ImmutableList.Builder<>();
    LocalDate endDate = clock.nowUtc().toLocalDate();
    for (LocalDate currentDate = START_DATE;
        !currentDate.isAfter(endDate);
        currentDate = currentDate.plusDays(1)) {
      if (!datesToSkip.contains(currentDate)) {
        result.add(currentDate);
      }
    }
    return result.build();
  }

  private ImmutableSet<LocalDate> getExistingDates() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "SELECT DISTINCT stm.checkDate FROM Spec11ThreatMatch stm", LocalDate.class)
                    .getResultStream()
                    .collect(toImmutableSet()));
  }
}
