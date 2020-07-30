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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.util.NonFinalForTesting;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Command to resave entities with a unique id.
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
@Parameters(separators = " =", commandDescription = "Resave entities with a unique id.")
public class ResaveEntitiesWithUniqueIdCommand extends MutatingCommand {

  @Parameter(
      names = "--key_paths_file",
      description =
          "Key paths file name, each line in the file should be a key literal. An example key"
              + " literal is: \"DomainBase\", \"111111-TEST\", \"HistoryEntry\", 2222222,"
              + " \"OneTime\", 3333333")
  File keyPathsFile;

  @NonFinalForTesting private static InputStream stdin = System.in;

  private String keyChangeMessage;

  @Override
  protected void init() throws Exception {
    for (String keyPath :
        keyPathsFile == null
            ? CharStreams.readLines(new InputStreamReader(stdin, UTF_8))
            : Files.readLines(keyPathsFile, UTF_8)) {
      Key<?> untypedKey = parseKeyPath(keyPath);
      Object entity = ofy().load().key(untypedKey).now();
      if (entity == null) {
        System.err.println(
            String.format(
                "Entity %s read from %s doesn't exist in Datastore!",
                untypedKey,
                keyPathsFile == null ? "STDIN" : "File " + keyPathsFile.getAbsolutePath()));
        continue;
      }
      if (entity instanceof BillingEvent.OneTime) {
        resaveBillingEvent((BillingEvent.OneTime) entity);
      } else {
        throw new IllegalArgumentException("Unsupported entity key: " + untypedKey);
      }
      flushTransaction();
    }
  }

  @Override
  protected void postBatchExecute() {
    System.out.println(keyChangeMessage);
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
    BillingEvent.OneTime uniqIdBillingEvent = billingEvent.asBuilder().setId(0L).build();
    deleteOldAndSaveNewEntity(billingEvent, uniqIdBillingEvent);
    keyChangeMessage =
        String.format("Old Entity Key: %s New Entity Key: %s", key, Key.create(uniqIdBillingEvent));
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
                      "Entity %s is referenced by the transfer data in domain %s",
                      key,
                      domainBase.createVKey().getOfyKey()));
    }
  }
}
