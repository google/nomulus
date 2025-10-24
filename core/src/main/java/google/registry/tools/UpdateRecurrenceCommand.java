// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.transfer.TransferStatus;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Command to update {@link BillingRecurrence} billing events with a new behavior and/or price.
 *
 * <p>More specifically, this closes the existing recurrence object and creates a new, similar,
 * object as well as a corresponding synthetic {@link DomainHistory} object. This is done to
 * preserve the recurrence's history.
 */
@Parameters(separators = " =", commandDescription = "Update a billing recurrence")
public class UpdateRecurrenceCommand extends ConfirmingCommand {

  private static final String HISTORY_REASON =
      "Administrative update of billing recurrence behavior";

  @Parameter(
      description = "Domain name(s) for which we wish to update the recurrence(s)",
      required = true)
  private List<String> mainParameters;

  @Nullable
  @Parameter(
      names = "--renewal_price_behavior",
      description = "New RenewalPriceBehavior value to use with this recurrence")
  RenewalPriceBehavior renewalPriceBehavior;

  @Nullable
  @Parameter(
      names = "--specified_renewal_price",
      description = "Exact renewal price to use if the behavior is SPECIFIED")
  Money specifiedRenewalPrice;

  @Override
  protected String prompt() throws Exception {
    checkArgument(
        renewalPriceBehavior != null || specifiedRenewalPrice != null,
        "Must specify a behavior and/or a price");

    if (renewalPriceBehavior != null) {
      if (renewalPriceBehavior.equals(RenewalPriceBehavior.SPECIFIED)) {
        checkArgument(
            specifiedRenewalPrice != null,
            "Renewal price must be set when using SPECIFIED behavior");
      } else {
        checkArgument(
            specifiedRenewalPrice == null,
            "Renewal price can have a value if and only if the renewal price behavior is"
                + " SPECIFIED");
      }
    }
    ImmutableMap<Domain, BillingRecurrence> domainsAndRecurrences =
        tm().transact(this::loadDomainsAndRecurrences);
    if (renewalPriceBehavior == null) {
      // Allow users to specify only a price only if all renewals are already SPECIFIED
      domainsAndRecurrences.forEach(
          (d, r) ->
              checkArgument(
                  r.getRenewalPriceBehavior().equals(RenewalPriceBehavior.SPECIFIED),
                  "When specifying only a price, all domains must have SPECIFIED behavior. Domain"
                      + " %s does not",
                  d.getDomainName()));
    }
    String specifiedPriceString =
        specifiedRenewalPrice == null ? "" : String.format(" and price %s", specifiedRenewalPrice);
    return String.format(
        "Update the following with behavior %s%s?\n%s",
        renewalPriceBehavior,
        specifiedPriceString,
        Joiner.on('\n').withKeyValueSeparator(':').join(domainsAndRecurrences));
  }

  @Override
  protected String execute() throws Exception {
    ImmutableList<BillingRecurrence> newBillingRecurrences = tm().transact(this::internalExecute);
    return "Updated new recurrence(s): " + newBillingRecurrences;
  }

  private ImmutableList<BillingRecurrence> internalExecute() {
    ImmutableMap<Domain, BillingRecurrence> domainsAndRecurrences = loadDomainsAndRecurrences();
    DateTime now = tm().getTransactionTime();
    ImmutableList.Builder<BillingRecurrence> resultBuilder = new ImmutableList.Builder<>();
    domainsAndRecurrences.forEach(
        (domain, existingRecurrence) -> {
          // Make a new history ID to break the (recurrence, history, domain) circular dep chain
          long newHistoryId = tm().allocateId();
          HistoryEntryId newDomainHistoryId = new HistoryEntryId(domain.getRepoId(), newHistoryId);
          BillingRecurrence endingNow =
              existingRecurrence.asBuilder().setRecurrenceEndTime(now).build();
          BillingRecurrence.Builder newRecurrenceBuilder =
              existingRecurrence
                  .asBuilder()
                  // set the ID to be 0 (null) to create a new object
                  .setId(0)
                  .setDomainHistoryId(newDomainHistoryId);
          if (renewalPriceBehavior != null) {
            newRecurrenceBuilder.setRenewalPriceBehavior(renewalPriceBehavior);
            newRecurrenceBuilder.setRenewalPrice(null);
          }
          if (specifiedRenewalPrice != null) {
            newRecurrenceBuilder.setRenewalPrice(specifiedRenewalPrice);
          }
          BillingRecurrence newBillingRecurrence = newRecurrenceBuilder.build();
          Domain newDomain =
              domain
                  .asBuilder()
                  .setAutorenewBillingEvent(newBillingRecurrence.createVKey())
                  .build();
          DomainHistory newDomainHistory =
              new DomainHistory.Builder()
                  .setRevisionId(newDomainHistoryId.getRevisionId())
                  .setDomain(newDomain)
                  .setReason(HISTORY_REASON)
                  .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                  .setBySuperuser(true)
                  .setRequestedByRegistrar(false)
                  .setType(HistoryEntry.Type.SYNTHETIC)
                  .setModificationTime(now)
                  .build();
          tm().putAll(endingNow, newBillingRecurrence, newDomain, newDomainHistory);
          resultBuilder.add(newBillingRecurrence);
        });
    return resultBuilder.build();
  }

  private ImmutableMap<Domain, BillingRecurrence> loadDomainsAndRecurrences() {
    ImmutableMap.Builder<Domain, BillingRecurrence> result = new ImmutableMap.Builder<>();
    DateTime now = tm().getTransactionTime();
    for (String domainName : mainParameters) {
      Domain domain =
          ForeignKeyUtils.loadResource(Domain.class, domainName, now)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format(
                              "Domain %s does not exist or has been deleted", domainName)));
      checkArgument(
          domain.getDeletionTime().equals(END_OF_TIME),
          "Domain %s has already had a deletion time set",
          domainName);
      checkArgument(
          domain.getTransferData().isEmpty()
              || domain.getTransferData().getTransferStatus() != TransferStatus.PENDING,
          "Domain %s has a pending transfer: %s",
          domainName,
          domain.getTransferData());
      Optional<DateTime> domainAutorenewEndTime = domain.getAutorenewEndTime();
      domainAutorenewEndTime.ifPresent(
          endTime ->
              checkArgument(
                  endTime.isAfter(now),
                  "Domain %s autorenew ended prior to now at %s",
                  domainName,
                  endTime));
      BillingRecurrence billingRecurrence = tm().loadByKey(domain.getAutorenewBillingEvent());
      checkArgument(
          billingRecurrence.getRecurrenceEndTime().equals(END_OF_TIME),
          "Domain %s's recurrence's end date is not END_OF_TIME",
          domainName);
      result.put(domain, billingRecurrence);
    }
    return result.build();
  }
}
