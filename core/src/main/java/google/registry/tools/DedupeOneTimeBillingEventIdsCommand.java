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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.beust.jcommander.Parameters;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.domain.DomainBase;

/**
 * Command to dedupe {@link BillingEvent.OneTime} entities having duplicate IDs.
 *
 * <p>This command is used to address the duplicate id issue we found for certain {@link
 * BillingEvent.OneTime} entities. The command reassigns an application wide unique id to the
 * problematic entity and resaves it, it also resaves the entity having reference to the problematic
 * entity with the updated id.
 *
 * <p>To use this command, you will need to provide the path to a file containing a list of strings
 * representing the literal of Objectify key for the problematic entities. An example key literal
 * is:
 *
 * <pre>
 * "DomainBase", "111111-TEST", "HistoryEntry", 2222222, "OneTime", 3333333
 * </pre>
 *
 * <p>Note that the double quotes are part of the key literal. The key literal can be retrieved from
 * the column <code>__key__.path</code> in BigQuery.
 */
@Parameters(
    separators = " =",
    commandDescription = "Dedupe BillingEvent.OneTime entities with duplicate IDs.")
public class DedupeOneTimeBillingEventIdsCommand extends DedupeEntityIdsCommand<OneTime> {

  @Override
  void dedupe(OneTime entity) {
    Key<BillingEvent> key = Key.create(entity);
    Key<DomainBase> domainKey = getGrandParentAsDomain(key);
    DomainBase domain = ofy().load().key(domainKey).now();

    // The BillingEvent.OneTime entity to be resaved should be the billing event created a few
    // years ago, so they should not be referenced from TransferData and GracePeriod in the domain.
    assertNotInDomainTransferData(domain, key);
    domain
        .getGracePeriods()
        .forEach(
            gracePeriod ->
                checkState(
                    !gracePeriod.getOneTimeBillingEvent().getOfyKey().equals(key),
                    "Entity %s is referenced by a grace period in domain %s",
                    key,
                    domainKey));

    // By setting id to 0L, Buildable.build() will assign an application wide unique id to it.
    BillingEvent.OneTime uniqIdBillingEvent = entity.asBuilder().setId(0L).build();
    stageEntityKeyChange(entity, uniqIdBillingEvent);
  }

  private static void assertNotInDomainTransferData(DomainBase domainBase, Key<?> key) {
    if (!domainBase.getTransferData().isEmpty()) {
      domainBase
          .getTransferData()
          .getServerApproveEntities()
          .forEach(
              entityKey ->
                  checkState(
                      !entityKey.getOfyKey().equals(key),
                      "Entity %s is referenced by the transfer data in domain %s",
                      key,
                      domainBase.createVKey().getOfyKey()));
    }
  }
}
