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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.tools.params.LongParameter;
import java.util.List;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Command to update a {@link Recurring} billing event with a new behavior or price.
 *
 * <p>More specifically, this closes the existing recurrence object and creates a new, similar,
 * object as well as a corresponding synthetic {@link DomainHistory} object. This is done to
 * preserve the recurrence's history.
 */
@Parameters(separators = " =", commandDescription = "Update a billing recurrence")
public class UpdateRecurrenceCommand extends ConfirmingCommand {

  @Parameter(
      description = "ID(s) of the BillingRecurrence event to modify",
      required = true,
      validateWith = LongParameter.class)
  private List<Long> mainParameters;

  @Parameter(
      names = "--renewal_price_behavior",
      description = "New RenewalPriceBehavior value to use with this recurrence")
  RenewalPriceBehavior renewalPriceBehavior;

  @Parameter(
      names = "--specified_renewal_price",
      description = "Exact renewal price to use if the behavior is SPECIFIED")
  Money specifiedRenewalPrice;

  @Override
  protected String prompt() throws Exception {
    checkArgument(
        renewalPriceBehavior.equals(RenewalPriceBehavior.SPECIFIED) ^ specifiedRenewalPrice == null,
        "Renewal price can only (and must) be set when using SPECIFIED behavior");
    ImmutableList<Recurring> recurrings = loadRecurrings();
    String specifiedPriceString =
        specifiedRenewalPrice == null ? "" : String.format(" and price %s", specifiedRenewalPrice);
    return String.format(
        "Update event(s) %s with behavior %s%s?",
        recurrings, renewalPriceBehavior, specifiedPriceString);
  }

  @Override
  protected String execute() throws Exception {
    ImmutableList<Recurring> newRecurrings = tm().transact(this::internalExecute);
    return "Updated new recurring(s): " + newRecurrings;
  }

  private ImmutableList<Recurring> internalExecute() {
    ImmutableList<Recurring> existingRecurrings = loadRecurrings();
    DateTime now = tm().getTransactionTime();
    ImmutableList.Builder<Recurring> resultBuilder = new ImmutableList.Builder<>();
    for (Recurring existing : existingRecurrings) {
      Domain domain = tm().loadByKey(VKey.create(Domain.class, existing.getDomainRepoId()));
      DomainHistory newDomainHistory =
          new DomainHistory.Builder()
              .setDomain(domain)
              .setReason(
                  String.format(
                      "Updating recurring with ID %s to have behavior %s",
                      existing.getId(), renewalPriceBehavior))
              .setRegistrarId(domain.getCurrentSponsorRegistrarId())
              .setBySuperuser(true)
              .setRequestedByRegistrar(false)
              .setType(HistoryEntry.Type.SYNTHETIC)
              .setModificationTime(now)
              .build();
      tm().put(newDomainHistory);
      Recurring endingNow = existing.asBuilder().setRecurrenceEndTime(now).build();
      Recurring newRecurring =
          existing
              .asBuilder()
              // set the ID to be 0 (null) to create a new object
              .setId(0)
              .setDomainHistory(newDomainHistory)
              .setRenewalPriceBehavior(renewalPriceBehavior)
              .setRenewalPrice(specifiedRenewalPrice)
              .build();
      tm().put(endingNow);
      tm().put(newRecurring);
      resultBuilder.add(newRecurring);
    }
    return resultBuilder.build();
  }

  private ImmutableList<Recurring> loadRecurrings() {
    ImmutableList<VKey<Recurring>> keys =
        mainParameters.stream()
            .map(s -> VKey.create(Recurring.class, s))
            .collect(toImmutableList());
    return ImmutableList.copyOf(tm().transact(() -> tm().loadByKeys(keys)).values());
  }
}
