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

package google.registry.tools;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.Set;

/**
 * A command that re-saves the problematic {@link BillingEvent.Recurring} entities with unique IDs.
 *
 * <p>This command is used to address the duplicate id issue we found for certain {@link
 * BillingEvent.Recurring} entities. The command reassigns an application wide unique id to the
 * problematic entity and resaves it, it also resaves the entity having reference to the problematic
 * entity with the updated id.
 *
 * <p>To use this command, you will need to provide the path to a file containing a list of strings
 * representing the literal of Objectify key for the problematic entities. An example key literal
 * is:
 *
 * <pre>
 * "DomainBase", "111111-TEST", "HistoryEntry", 2222222, "Recurring", 3333333
 * </pre>
 *
 * <p>Note that the double quotes are part of the key literal. The key literal can be retrieved from
 * the column <code>__key__.path</code> in BigQuery.
 */
@Parameters(
    separators = " =",
    commandDescription = "Dedupe BillingEvent.Recurring entities with duplicate IDs.")
public class DedupeRecurringBillingEventIdsCommand extends ReadEntityFromKeyPathCommand<Recurring> {

  @Override
  void process(Recurring recurring) {
    // Loads the associated DomainBase and BillingEvent.OneTime entities that
    // may have reference to this BillingEvent.Recurring entity.
    Key<DomainBase> domainKey = getGrandParentAsDomain(Key.create(recurring));
    DomainBase domain = ofy().load().key(domainKey).now();
    List<BillingEvent.OneTime> oneTimes =
        ofy().load().type(BillingEvent.OneTime.class).ancestor(domainKey).list();

    VKey<Recurring> oldRecurringVKey = recurring.createVKey();
    // By setting id to 0L, Buildable.build() will assign an application wide unique id to it.
    Recurring uniqIdRecurring = recurring.asBuilder().setId(0L).build();
    VKey<Recurring> newRecurringVKey = uniqIdRecurring.createVKey();

    // After having the unique id for the BillingEvent.Recurring entity, we also need to
    // update the references in other entities to point to the new BillingEvent.Recurring
    // entity.
    updateReferenceInOneTimeBillingEvent(oneTimes, oldRecurringVKey, newRecurringVKey);
    updateReferenceInDomain(domain, oldRecurringVKey, newRecurringVKey);

    stageEntityKeyChange(recurring, uniqIdRecurring);
  }

  /**
   * Resaves {@link BillingEvent.OneTime} entities with updated {@link
   * BillingEvent.OneTime#cancellationMatchingBillingEvent}.
   *
   * <p>{@link BillingEvent.OneTime#cancellationMatchingBillingEvent} is a {@link VKey} to a {@link
   * BillingEvent.Recurring} entity. So, if the {@link BillingEvent.Recurring} entity gets a new key
   * by changing its id, we need to update {@link
   * BillingEvent.OneTime#cancellationMatchingBillingEvent} as well.
   */
  private void updateReferenceInOneTimeBillingEvent(
      List<OneTime> oneTimes, VKey<Recurring> oldRecurringVKey, VKey<Recurring> newRecurringVKey) {
    oneTimes.forEach(
        oneTime -> {
          if (oneTime.getCancellationMatchingBillingEvent() != null
              && oneTime.getCancellationMatchingBillingEvent().equals(oldRecurringVKey)) {
            BillingEvent.OneTime updatedOneTime =
                oneTime.asBuilder().setCancellationMatchingBillingEvent(newRecurringVKey).build();
            stageEntityChange(oneTime, updatedOneTime);
            appendChangeMessage(
                String.format(
                    "Changed cancellationMatchingBillingEvent in entity %s from %s to %s\n",
                    oneTime.createVKey().getOfyKey(),
                    oneTime.getCancellationMatchingBillingEvent().getOfyKey(),
                    updatedOneTime.getCancellationMatchingBillingEvent().getOfyKey()));
          }
        });
  }

  /**
   * Resaves {@link DomainBase} entity with updated references to {@link BillingEvent.Recurring}
   * entity.
   *
   * <p>The following 4 fields in the domain entity can be or have a reference to this
   * BillingEvent.Recurring entity, so we need to check them and replace them with the new entity
   * when necessary:
   *
   * <ol>
   *   <li>domain.autorenewBillingEvent, see {@link DomainBase#autorenewBillingEvent}
   *   <li>domain.transferData.serverApproveAutorenewEvent, see {@link
   *       DomainTransferData#serverApproveAutorenewEvent}
   *   <li>domain.transferData.serverApproveEntities, see {@link
   *       DomainTransferData#serverApproveEntities}
   *   <li>domain.gracePeriods.billingEventRecurring, see {@link GracePeriod#billingEventRecurring}
   * </ol>
   */
  private void updateReferenceInDomain(
      DomainBase domain, VKey<Recurring> oldRecurringVKey, VKey<Recurring> newRecurringVKey) {
    DomainBase.Builder domainBuilder = domain.asBuilder();
    StringBuilder domainChange =
        new StringBuilder(
            String.format(
                "Resaved domain %s with following changes:\n", domain.createVKey().getOfyKey()));

    if (domain.getAutorenewBillingEvent() != null
        && domain.getAutorenewBillingEvent().equals(oldRecurringVKey)) {
      domainBuilder.setAutorenewBillingEvent(newRecurringVKey);
      domainChange.append(
          String.format(
              "  Changed autorenewBillingEvent from %s to %s.\n",
              oldRecurringVKey, newRecurringVKey));
    }

    if (domain.getTransferData().getServerApproveAutorenewEvent() != null
        && domain.getTransferData().getServerApproveAutorenewEvent().equals(oldRecurringVKey)) {
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities =
          Sets.union(
              Sets.difference(
                  domain.getTransferData().getServerApproveEntities(),
                  ImmutableSet.of(oldRecurringVKey)),
              ImmutableSet.of(newRecurringVKey));
      domainBuilder.setTransferData(
          domain
              .getTransferData()
              .asBuilder()
              .setServerApproveEntities(ImmutableSet.copyOf(serverApproveEntities))
              .setServerApproveAutorenewEvent(newRecurringVKey)
              .build());
      domainChange.append(
          String.format(
              "  Changed transferData.serverApproveAutoRenewEvent from %s to %s.\n",
              oldRecurringVKey, newRecurringVKey));
      domainChange.append(
          String.format(
              "  Changed transferData.serverApproveEntities to remove %s and add %s.\n",
              oldRecurringVKey, newRecurringVKey));
    }

    ImmutableSet<GracePeriod> updatedGracePeriod =
        domain.getGracePeriods().stream()
            .map(
                gracePeriod ->
                    gracePeriod.getRecurringBillingEvent().equals(oldRecurringVKey)
                        ? gracePeriod.cloneWithRecurringBillingEvent(newRecurringVKey)
                        : gracePeriod)
            .collect(toImmutableSet());
    if (!updatedGracePeriod.equals(domain.getGracePeriods())) {
      domainBuilder.setGracePeriods(updatedGracePeriod);
      domainChange.append(
          String.format(
              "  Changed gracePeriods to remove %s and add %s.\n",
              oldRecurringVKey, newRecurringVKey));
    }

    DomainBase updatedDomain = domainBuilder.build();
    if (!updatedDomain.equals(domain)) {
      stageEntityChange(domain, updatedDomain);
      appendChangeMessage(domainChange.toString());
    }
  }
}
