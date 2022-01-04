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
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.domain.DomainBase;
import google.registry.persistence.VKey;
import google.registry.util.NonFinalForTesting;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Base Command to read entities from Datastore by their key paths retrieved from BigQuery.
 *
 * <p>The key path is the value of column __key__.path of the entity's BigQuery table. Its value is
 * converted from the entity's key.
 */
@DeleteAfterMigration
abstract class ReadEntityFromKeyPathCommand<T> extends MutatingCommand {

  @Parameter(
      names = "--key_paths_file",
      description =
          "Key paths file name, each line in the file should be a key literal. An example key"
              + " literal is: \"DomainBase\", \"111111-TEST\", \"HistoryEntry\", 2222222,"
              + " \"OneTime\", 3333333")
  File keyPathsFile;

  @NonFinalForTesting private static InputStream stdin = System.in;

  private StringBuilder changeMessage = new StringBuilder();

  abstract void process(T entity);

  @Override
  protected void init() throws Exception {
    List<String> keyPaths =
        keyPathsFile == null
            ? CharStreams.readLines(new InputStreamReader(stdin, UTF_8))
            : Files.readLines(keyPathsFile, UTF_8);
    for (String keyPath : keyPaths) {
      Key<?> untypedKey = parseKeyPath(keyPath);
      Object entity = auditedOfy().load().key(untypedKey).now();
      if (entity == null) {
        System.err.printf(
            "Entity %s read from %s doesn't exist in Datastore! Skipping.%n",
            untypedKey, keyPathsFile == null ? "STDIN" : "File " + keyPathsFile.getAbsolutePath());
        continue;
      }
      Class<T> clazz = new TypeInstantiator<T>(getClass()) {}.getExactType();
      if (clazz.isInstance(entity)) {
        process((T) entity);
      } else {
        throw new IllegalArgumentException("Unsupported entity key: " + untypedKey);
      }
      flushTransaction();
    }
  }

  @Override
  protected void postBatchExecute() {
    System.out.println(changeMessage);
  }

  void stageEntityKeyChange(ImmutableObject oldEntity, ImmutableObject newEntity) {
    stageEntityChange(oldEntity, null);
    stageEntityChange(null, newEntity);
    appendChangeMessage(
        String.format(
            "Changed entity key from: %s to: %s", Key.create(oldEntity), Key.create(newEntity)));
  }

  void appendChangeMessage(String message) {
    changeMessage.append(message);
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

  static Key<DomainBase> getGrandParentAsDomain(Key<?> key) {
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

  static VKey<DomainBase> getGrandParentAsDomain(VKey<?> key) {
    Key<DomainBase> grandParent;
    try {
      grandParent = key.getOfyKey().getParent().getParent();
    } catch (Throwable e) {
      throw new IllegalArgumentException("Error retrieving grand parent key", e);
    }
    if (!isKind(grandParent, DomainBase.class)) {
      throw new IllegalArgumentException(
          String.format("Expected a Key<DomainBase> but got %s", grandParent));
    }
    return VKey.create(DomainBase.class, grandParent.getName(), grandParent);
  }
}
