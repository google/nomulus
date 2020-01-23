// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.leapSafeSubtractYears;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.util.Clock;
import google.registry.util.NonFinalForTesting;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Command to unrenew a domain.
 *
 * <p>This removes years off a domain's registration period. Note that the expiration time cannot be
 * set to prior than the present. Reversal of the charges for these years (if desired) must happen
 * out of band, as they may already have been billed out and thus cannot and won't be reversed in
 * Datastore.
 */
@Parameters(separators = " =", commandDescription = "Unrenew a domain.")
@NonFinalForTesting
class UnrenewDomainCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-p", "--period"},
      description = "Number of years to unrenew the registration for (defaults to 1).")
  int period = 1;

  @Parameter(description = "Names of the domains to unrenew.", required = true)
  List<String> mainParameters;

  @Inject Clock clock;

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES =
      ImmutableSet.of(
          StatusValue.PENDING_TRANSFER,
          StatusValue.SERVER_RENEW_PROHIBITED,
          StatusValue.SERVER_UPDATE_PROHIBITED);

  @Override
  protected void init() {
    checkArgument(period >= 1 && period <= 9, "Period must be in the range 1-9");
    DateTime now = clock.nowUtc();
    ImmutableSet.Builder<String> domainsNonexistentBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> domainsDeletingBuilder = new ImmutableSet.Builder<>();
    ImmutableMultimap.Builder<String, StatusValue> domainsWithDisallowedStatusesBuilder =
        new ImmutableMultimap.Builder<>();
    ImmutableMap.Builder<String, DateTime> domainsExpiringTooSoonBuilder =
        new ImmutableMap.Builder<>();

    for (String domainName : mainParameters) {
      if (ofy().load().type(ForeignKeyDomainIndex.class).id(domainName).now() == null) {
        domainsNonexistentBuilder.add(domainName);
        continue;
      }
      Optional<DomainBase> domain = loadByForeignKey(DomainBase.class, domainName, now);
      if (!domain.isPresent()
          || domain.get().getStatusValues().contains(StatusValue.PENDING_DELETE)) {
        domainsDeletingBuilder.add(domainName);
        continue;
      }
      domainsWithDisallowedStatusesBuilder.putAll(
          domainName, Sets.intersection(domain.get().getStatusValues(), DISALLOWED_STATUSES));
      if (isBeforeOrAt(
          leapSafeSubtractYears(domain.get().getRegistrationExpirationTime(), period), now)) {
        domainsExpiringTooSoonBuilder.put(domainName, domain.get().getRegistrationExpirationTime());
      }
    }

    ImmutableSet<String> domainsNonexistent = domainsNonexistentBuilder.build();
    ImmutableSet<String> domainsDeleting = domainsDeletingBuilder.build();
    ImmutableMultimap<String, StatusValue> domainsWithDisallowedStatuses =
        domainsWithDisallowedStatusesBuilder.build();
    ImmutableMap<String, DateTime> domainsExpiringTooSoon = domainsExpiringTooSoonBuilder.build();

    boolean foundInvalidDomains =
        !(domainsNonexistent.isEmpty()
            && domainsDeleting.isEmpty()
            && domainsWithDisallowedStatuses.isEmpty()
            && domainsExpiringTooSoon.isEmpty());
    if (foundInvalidDomains) {
      System.err.print("Found domains that cannot be unrenewed for the following reasons:\n\n");
    }
    if (!domainsNonexistent.isEmpty()) {
      System.err.printf("Domains that don't exist: %s\n\n", domainsNonexistent);
    }
    if (!domainsDeleting.isEmpty()) {
      System.err.printf("Domains that are deleted or pending delete: %s\n\n", domainsDeleting);
    }
    if (!domainsWithDisallowedStatuses.isEmpty()) {
      System.err.printf("Domains with disallowed statuses: %s\n\n", domainsWithDisallowedStatuses);
    }
    if (!domainsExpiringTooSoon.isEmpty()) {
      System.err.printf("Domains expiring too soon: %s\n\n", domainsExpiringTooSoon);
    }
    checkArgument(!foundInvalidDomains, "Aborting because some domains cannot be unrewed");
  }

  @Override
  protected String prompt() {
    StringBuilder resultBuilder = new StringBuilder();
    DateTime now = clock.nowUtc();
    for (String domainName : mainParameters) {
      DomainBase domain = loadByForeignKey(DomainBase.class, domainName, now).get();
      DateTime previousTime = domain.getRegistrationExpirationTime();
      DateTime newTime = leapSafeSubtractYears(previousTime, period);
      resultBuilder.append(
          String.format(
              "%s expiration time changed from %s to %s\n", domainName, previousTime, newTime));
    }
    resultBuilder.append(String.format("Unrenew these domains(s) for %d years?", period));
    return resultBuilder.toString();
  }

  @Override
  protected String execute() {
    for (String domainName : mainParameters) {
      tm().transact(() -> unrenewDomain(domainName));
      System.out.printf("Unrenewed %s\n", domainName);
    }
    return "Successfully unrenewed all domains.";
  }

  private void unrenewDomain(String domainName) {
    tm().assertInTransaction();
    DateTime now = tm().getTransactionTime();
    Optional<DomainBase> domainOptional =
        loadByForeignKey(DomainBase.class, domainName, now);
    // Transactional sanity checks on the off chance that something changed between init() running
    // and here.
    checkState(
        domainOptional.isPresent()
            && !domainOptional.get().getStatusValues().contains(StatusValue.PENDING_DELETE),
        "Domain %s was deleted or is pending deletion",
        domainName);
    DomainBase domain = domainOptional.get();
    checkState(
        Sets.intersection(domain.getStatusValues(), DISALLOWED_STATUSES).isEmpty(),
        "Domain %s has prohibited status values",
        domainName);
    checkState(
        leapSafeSubtractYears(domain.getRegistrationExpirationTime(), period).isAfter(now),
        "Domain %s expires too soon",
        domainName);

    DateTime newExpirationTime =
        leapSafeSubtractYears(domain.getRegistrationExpirationTime(), period);
    HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setParent(domain)
            .setModificationTime(now)
            .setBySuperuser(true)
            .setType(Type.SYNTHETIC)
            .setClientId(domain.getCurrentSponsorClientId())
            .setReason("Domain unrenewal")
            .setPeriod(Period.create(period, Unit.YEARS))
            .setRequestedByRegistrar(false)
            .build();
    PollMessage oneTimePollMessage =
        new PollMessage.OneTime.Builder()
            .setClientId(domain.getCurrentSponsorClientId())
            .setMsg(
                String.format(
                    "Domain %s was unrenewed by %d years; now expires at %s.",
                    domainName, period, newExpirationTime))
            .setParent(historyEntry)
            .setEventTime(now)
            .build();
    // Create a new autorenew billing event and poll message starting at the new expiration time.
    BillingEvent.Recurring newAutorenewEvent =
        newAutorenewBillingEvent(domain)
            .setEventTime(newExpirationTime)
            .setParent(historyEntry)
            .build();
    PollMessage.Autorenew newAutorenewPollMessage =
        newAutorenewPollMessage(domain)
            .setEventTime(newExpirationTime)
            .setParent(historyEntry)
            .build();
    // End the old autorenew billing event and poll message now.
    updateAutorenewRecurrenceEndTime(domain, now);
    DomainBase newDomain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(newExpirationTime)
            .setLastEppUpdateTime(now)
            .setLastEppUpdateClientId(domain.getCurrentSponsorClientId())
            .setAutorenewBillingEvent(Key.create(newAutorenewEvent))
            .setAutorenewPollMessage(Key.create(newAutorenewPollMessage))
            .build();
    // In order to do it'll need to write out a new HistoryEntry (likely of type SYNTHETIC), a new
    // autorenew billing event and poll message, and a new one time poll message at the present time
    // informing the registrar of this out-of-band change.
    ofy()
        .save()
        .entities(
            newDomain,
            historyEntry,
            oneTimePollMessage,
            newAutorenewEvent,
            newAutorenewPollMessage);
  }
}
