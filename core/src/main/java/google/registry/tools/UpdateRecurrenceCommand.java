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
import static google.registry.model.IdService.allocateId;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.EppResourceUtils;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Command to update {@link Recurring} billing events with a new behavior and/or price.
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
    ImmutableMap<Domain, Recurring> domainsAndRecurrings =
        tm().transact(this::loadDomainsAndRecurrings);
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
    } else {
      // Allow users to specify only a price only if all renewals are already SPECIFIED
      domainsAndRecurrings.forEach(
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
        Joiner.on('\n').withKeyValueSeparator(':').join(domainsAndRecurrings));
  }

  @Override
  protected String execute() throws Exception {
    ImmutableList<Recurring> newRecurrings = tm().transact(this::internalExecute);
    return "Updated new recurring(s): " + newRecurrings;
  }

  private ImmutableList<Recurring> internalExecute() {
    ImmutableMap<Domain, Recurring> domainsAndRecurrings = loadDomainsAndRecurrings();
    DateTime now = tm().getTransactionTime();
    ImmutableList.Builder<Recurring> resultBuilder = new ImmutableList.Builder<>();
    domainsAndRecurrings.forEach(
        (domain, existingRecurring) -> {
          // Make a new history ID to break the (recurring, history, domain) circular dep chain
          long newHistoryId = allocateId();
          HistoryEntryId newDomainHistoryId = new HistoryEntryId(domain.getRepoId(), newHistoryId);
          Recurring endingNow = existingRecurring.asBuilder().setRecurrenceEndTime(now).build();
          Recurring.Builder newRecurringBuilder =
              existingRecurring
                  .asBuilder()
                  // set the ID to be 0 (null) to create a new object
                  .setId(0)
                  .setDomainHistoryId(newDomainHistoryId);
          if (renewalPriceBehavior != null) {
            newRecurringBuilder.setRenewalPriceBehavior(renewalPriceBehavior);
            newRecurringBuilder.setRenewalPrice(null);
          }
          if (specifiedRenewalPrice != null) {
            newRecurringBuilder.setRenewalPrice(specifiedRenewalPrice);
          }
          Recurring newRecurring = newRecurringBuilder.build();
          Domain newDomain =
              domain.asBuilder().setAutorenewBillingEvent(newRecurring.createVKey()).build();
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
          tm().putAll(endingNow, newRecurring, newDomain, newDomainHistory);
          resultBuilder.add(newRecurring);
        });
    return resultBuilder.build();
  }

  private ImmutableMap<Domain, Recurring> loadDomainsAndRecurrings() {
    ImmutableMap.Builder<Domain, Recurring> result = new ImmutableMap.Builder<>();
    DateTime now = tm().getTransactionTime();
    for (String domainName : mainParameters) {
      Domain domain =
          EppResourceUtils.loadByForeignKey(Domain.class, domainName, now)
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
          domain.getTransferData().isEmpty(),
          "Domain %s has a pending transfer: %s",
          domainName,
          domain.getTransferData());
      Recurring recurring = tm().loadByKey(domain.getAutorenewBillingEvent());
      checkArgument(
          recurring.getRecurrenceEndTime().equals(END_OF_TIME),
          "Domain %s's recurrence's end date is not END_OF_TIME",
          domainName);
      result.put(domain, recurring);
    }
    return result.build();
  }
}
