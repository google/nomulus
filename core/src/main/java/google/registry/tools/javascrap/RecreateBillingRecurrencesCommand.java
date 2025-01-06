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

package google.registry.tools.javascrap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.model.EppResourceUtils;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.common.TimeOfYear;
import google.registry.model.domain.Domain;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.tools.ConfirmingCommand;
import java.util.List;
import org.joda.time.DateTime;

/**
 * Command to recreate closed {@link BillingRecurrence}s for domains.
 *
 * <p>This can be used to fix situations where BillingRecurrences were inadvertently closed. The new
 * recurrences will start at the recurrenceTimeOfYear that has most recently occurred in the past,
 * so that billing will restart upon the next date that the domain would have normally been billed
 * for autorenew.
 */
@Parameters(
    separators = " =",
    commandDescription = "Recreate inadvertently-closed BillingRecurrences.")
public class RecreateBillingRecurrencesCommand extends ConfirmingCommand {

  @Parameter(
      description = "Domain name(s) for which we wish to recreate a BillingRecurrence",
      required = true)
  private List<String> mainParameters;

  @Override
  protected String prompt() throws Exception {
    checkArgument(!mainParameters.isEmpty(), "Must provide at least one domain name");
    return tm().transact(
            () -> {
              ImmutableList<BillingRecurrence> existingRecurrences = loadRecurrences();
              ImmutableList<BillingRecurrence> newRecurrences =
                  convertRecurrencesWithoutSaving(existingRecurrences);
              return String.format(
                  """
                      Create new BillingRecurrence(s)?
                      Existing recurrences:
                      %s
                      New recurrences:
                      %s""",
                  Joiner.on('\n').join(existingRecurrences), Joiner.on('\n').join(newRecurrences));
            });
  }

  @Override
  protected String execute() throws Exception {
    ImmutableList<BillingRecurrence> newBillingRecurrences = tm().transact(this::internalExecute);
    return "Created new recurrence(s): " + newBillingRecurrences;
  }

  private ImmutableList<BillingRecurrence> internalExecute() {
    ImmutableList<BillingRecurrence> newRecurrences =
        convertRecurrencesWithoutSaving(loadRecurrences());
    newRecurrences.forEach(
        recurrence -> {
          tm().put(recurrence);
          Domain domain = tm().loadByKey(VKey.create(Domain.class, recurrence.getDomainRepoId()));
          tm().put(domain.asBuilder().setAutorenewBillingEvent(recurrence.createVKey()).build());
        });
    return newRecurrences;
  }

  private ImmutableList<BillingRecurrence> convertRecurrencesWithoutSaving(
      ImmutableList<BillingRecurrence> existingRecurrences) {
    return existingRecurrences.stream()
        .map(
            existingRecurrence -> {
              TimeOfYear timeOfYear = existingRecurrence.getRecurrenceTimeOfYear();
              DateTime newLastExpansion =
                  timeOfYear.getLastInstanceBeforeOrAt(tm().getTransactionTime());
              // event time should be the next date of billing in the future
              DateTime eventTime = timeOfYear.getNextInstanceAtOrAfter(tm().getTransactionTime());
              return existingRecurrence
                  .asBuilder()
                  .setRecurrenceEndTime(END_OF_TIME)
                  .setRecurrenceLastExpansion(newLastExpansion)
                  .setEventTime(eventTime)
                  .setId(0)
                  .build();
            })
        .collect(toImmutableList());
  }

  private ImmutableList<BillingRecurrence> loadRecurrences() {
    ImmutableList.Builder<BillingRecurrence> result = new ImmutableList.Builder<>();
    DateTime now = tm().getTransactionTime();
    for (String domainName : mainParameters) {
      Domain domain =
          EppResourceUtils.loadByForeignKey(Domain.class, domainName, now)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format(
                              "Domain %s does not exist or has been deleted", domainName)));
      BillingRecurrence billingRecurrence = tm().loadByKey(domain.getAutorenewBillingEvent());
      checkArgument(
          !billingRecurrence.getRecurrenceEndTime().equals(END_OF_TIME),
          "Domain %s's recurrence's end date is already END_OF_TIME",
          domainName);
      // Double-check that there are no non-linked BillingRecurrences that have an END_OF_TIME end.
      // If this is the case, something has been mis-linked.
      ImmutableList<BillingRecurrence> allRecurrencesForDomain =
          tm().createQueryComposer(BillingRecurrence.class)
              .where("domainRepoId", Comparator.EQ, domain.getRepoId())
              .list();
      allRecurrencesForDomain.forEach(
          recurrence ->
              checkArgument(
                  !recurrence.getRecurrenceEndTime().equals(END_OF_TIME),
                  "There exists a recurrence with id %s for domain %s with an end date of"
                      + " END_OF_TIME",
                  recurrence.getId(),
                  domainName));

      result.add(billingRecurrence);
    }
    return result.build();
  }
}
