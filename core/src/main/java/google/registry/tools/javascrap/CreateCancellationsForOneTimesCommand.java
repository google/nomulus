// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Cancellation;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.tools.params.LongParameter;
import java.util.List;

/**
 * Command to create {@link Cancellation}s for specified {@link OneTime} billing events.
 *
 * <p>This can be used to fix situations where we've inadvertently billed registrars. It's generally
 * easier and better to issue cancellations within the Nomulus system than to attempt to issue
 * refunds after the fact.
 */
@Parameters(separators = " =", commandDescription = "Manually create Cancellations for OneTimes.")
public class CreateCancellationsForOneTimesCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(
      description = "Space-delimited billing event ID(s) to cancel",
      required = true,
      validateWith = LongParameter.class)
  private List<Long> mainParameters;

  private ImmutableSet<OneTime> oneTimesToCancel;

  @Override
  protected void init() {
    ImmutableSet.Builder<Long> missingIdsBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<Long> alreadyCancelledIdsBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<OneTime> oneTimesBuilder = new ImmutableSet.Builder<>();
    tm().transact(
            () -> {
              for (Long billingEventId : ImmutableSet.copyOf(mainParameters)) {
                VKey<OneTime> key = VKey.createSql(OneTime.class, billingEventId);
                if (tm().exists(key)) {
                  OneTime oneTime = tm().loadByKey(key);
                  if (alreadyCancelled(oneTime)) {
                    alreadyCancelledIdsBuilder.add(billingEventId);
                  } else {
                    oneTimesBuilder.add(oneTime);
                  }
                } else {
                  missingIdsBuilder.add(billingEventId);
                }
              }
            });
    oneTimesToCancel = oneTimesBuilder.build();
    System.out.printf("Found %d OneTime(s) to cancel\n", oneTimesToCancel.size());
    ImmutableSet<Long> missingIds = missingIdsBuilder.build();
    if (!missingIds.isEmpty()) {
      System.out.printf("Missing OneTime(s) for IDs %s\n", missingIds);
    }
    ImmutableSet<Long> alreadyCancelledIds = alreadyCancelledIdsBuilder.build();
    if (!alreadyCancelledIds.isEmpty()) {
      System.out.printf(
          "The following OneTime IDs were already cancelled: %s\n", alreadyCancelledIds);
    }
  }

  @Override
  protected String prompt() {
    return String.format("Create cancellations for %d OneTime(s)?", oneTimesToCancel.size());
  }

  @Override
  protected String execute() throws Exception {
    int cancelledOneTimes = 0;
    for (OneTime oneTime : oneTimesToCancel) {
      cancelledOneTimes +=
          tm().transact(
                  () -> {
                    if (alreadyCancelled(oneTime)) {
                      System.out.printf(
                          "OneTime %d already cancelled, this is unexpected.\n", oneTime.getId());
                      return 0;
                    }
                    tm().put(
                            new Cancellation.Builder()
                                .setOneTimeEventKey(oneTime.createVKey())
                                .setBillingTime(oneTime.getBillingTime())
                                .setDomainHistoryId(oneTime.getHistoryEntryId())
                                .setRegistrarId(oneTime.getRegistrarId())
                                .setEventTime(oneTime.getEventTime())
                                .setReason(BillingEvent.Reason.ERROR)
                                .setTargetId(oneTime.getTargetId())
                                .build());
                    System.out.printf(
                        "Added Cancellation for OneTime with ID %d\n", oneTime.getId());
                    return 1;
                  });
    }
    return String.format("Created %d Cancellation event(s)", cancelledOneTimes);
  }

  private boolean alreadyCancelled(OneTime oneTime) {
    return tm().createQueryComposer(Cancellation.class)
        .where("refOneTime", Comparator.EQ, oneTime.getId())
        .first()
        .isPresent();
  }
}
