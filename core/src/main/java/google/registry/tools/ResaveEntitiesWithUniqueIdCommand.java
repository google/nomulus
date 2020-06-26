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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.common.base.Splitter;
import com.google.common.io.Files;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.model.poll.PollMessage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Command to resave entities with a unique id.
 *
 * <p>This command is used to address the duplicate id issue we found for certain
 * BillingEvent.OneTime and PollMessage.Autorenew entities. The command reassigns an application
 * wide unique id to the problematic entity and resaves it, it also resaves the entity having
 * reference to the problematic entity with the updated id.
 */
@Parameters(separators = " =", commandDescription = "Resave entites with a unique id.")
public class ResaveEntitiesWithUniqueIdCommand extends MutatingCommand {

  @Parameter(description = "Key paths file name", required = true)
  List<String> mainParameters;

  @Override
  protected void init() throws Exception {
    for (String fileName : mainParameters) {
      for (String keyPath : Files.readLines(new File(fileName), StandardCharsets.UTF_8)) {
        Key<?> untypedKey = parseKeyPath(keyPath);
        Object entity = ofy().load().key(untypedKey).now();
        if (entity == null) {
          System.err.println(String.format("Entity %s doesn't exist!", untypedKey));
          continue;
        }
        if (entity instanceof BillingEvent.OneTime) {
          resaveBillingEvent((BillingEvent.OneTime) entity);
        } else if (entity instanceof PollMessage.Autorenew) {
          resaveAutorenewPollMessage((PollMessage.Autorenew) entity);
        } else {
          throw new IllegalArgumentException("Unsupported entity key: " + untypedKey);
        }
        flushTransaction();
      }
    }
  }

  private void deleteOldAndSaveNewEntity(ImmutableObject oldEntity, ImmutableObject newEntity) {
    stageEntityChange(oldEntity, null);
    stageEntityChange(null, newEntity);
  }

  private void resaveBillingEvent(BillingEvent.OneTime billingEvent) {
    Key<BillingEvent> key = Key.create(billingEvent);
    Key<DomainBase> domainKey = getGrandParentAsDomain(key);
    DomainBase domain = ofy().load().key(domainKey).now();

    // The BillingEvent.OneTime entity to be resaved should be the billing event created a few
    // years ago, so they should not be referred in TransferData and GracePeriod in the domain.
    assertNotInDomainTransferData(domain, key);
    domain
        .getGracePeriods()
        .forEach(
            gracePeriod ->
                checkState(
                    !gracePeriod.getOneTimeBillingEvent().equals(key),
                    "Entity %s is referred by a grace period in domain %s",
                    key,
                    domainKey));

    // By setting id to 0L, Buildable.build() will assign an application wide unique id to it.
    BillingEvent.OneTime uniqIdBillingEvent = billingEvent.asBuilder().setId(0L).build();
    deleteOldAndSaveNewEntity(billingEvent, uniqIdBillingEvent);
  }

  private void resaveAutorenewPollMessage(PollMessage.Autorenew pollMessage) {
    Key<PollMessage.Autorenew> key = Key.create(pollMessage);
    Key<DomainBase> domainKey = getGrandParentAsDomain(key);
    DomainBase domain = ofy().load().key(domainKey).now();

    assertNotInDomainTransferData(domain, key);

    // It is possible that the PollMessage.Autorenew entity to be resaved is still referred by its
    // domain.
    PollMessage.Autorenew uniqIdPollMessage = pollMessage.asBuilder().setId(0L).build();
    if (domain.getAutorenewPollMessage().equals(key)) {
      stageEntityChange(
          domain,
          domain.asBuilder().setAutorenewPollMessage(Key.create(uniqIdPollMessage)).build());
    }
    deleteOldAndSaveNewEntity(pollMessage, uniqIdPollMessage);
  }

  private static boolean isKind(Key<?> key, Class<?> clazz) {
    return key.getKind().equals(Key.getKind(clazz));
  }

  static Key<?> parseKeyPath(String keyPath) {
    List<String> keyComponents = Splitter.on(',').splitToList(keyPath);
    checkState(
        keyComponents.size() > 0 && keyComponents.size() % 2 == 0,
        "Invalid number of key components");
    com.google.appengine.api.datastore.Key rawKey = null;
    for (int i = 0, j = 1; j < keyComponents.size(); i += 2, j += 2) {
      String kindLiteral = keyComponents.get(i).trim();
      String idOrNameLiteral = keyComponents.get(j).trim();
      rawKey = createDatastoreKey(rawKey, kindLiteral, idOrNameLiteral);
    }
    return Key.create(rawKey);
  }

  private static com.google.appengine.api.datastore.Key createDatastoreKey(
      com.google.appengine.api.datastore.Key parent, String kindLiteral, String idOrNameLiteral) {
    if (isLiteralString(idOrNameLiteral)) {
      return KeyFactory.createKey(parent, removeQuotes(kindLiteral), removeQuotes(idOrNameLiteral));
    } else {
      return KeyFactory.createKey(
          parent, removeQuotes(kindLiteral), Long.parseLong(idOrNameLiteral));
    }
  }

  private static boolean isLiteralString(String raw) {
    return raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"';
  }

  private static String removeQuotes(String literal) {
    return literal.substring(1, literal.length() - 1);
  }

  private static Key<DomainBase> getGrandParentAsDomain(Key<?> key) {
    Key<?> grandParent;
    try {
      grandParent = key.getParent().getParent();
    } catch (Throwable e) {
      throw new IllegalArgumentException("Error retrieving grand parent key", e);
    }
    if (!isKind(grandParent, DomainBase.class)) {
      throw new IllegalArgumentException(
          String.format("Expected a Key<DomainBase> but got %s", grandParent));
    }
    return (Key<DomainBase>) grandParent;
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
                      "Entity %s is referred by the transfer data in domain %s",
                      key,
                      domainBase.createVKey().getOfyKey()));
    }
  }
}
