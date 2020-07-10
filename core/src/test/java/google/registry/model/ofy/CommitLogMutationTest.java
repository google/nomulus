// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.ofy;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.createTld;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.appengine.api.datastore.KeyFactory;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CommitLogMutation}. */
public class CommitLogMutationTest {

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private static final DateTime NOW = DateTime.now(DateTimeZone.UTC);

  private Key<CommitLogManifest> manifestKey;
  private ImmutableObject someObject;

  @BeforeEach
  void before() {
    // Initialize this late to avoid dependency on NamespaceManager prior to AppEngineRule.
    manifestKey = CommitLogManifest.createKey(CommitLogBucket.getBucketKey(1), NOW);
    createTld("tld");
    someObject = Registry.get("tld");
  }

  @Test
  void test_createKey_createsKeyWithWebsafeKeystring() {
    Key<CommitLogMutation> mutationKey =
        CommitLogMutation.createKey(manifestKey, Key.create(someObject));
    assertThat(mutationKey.getParent()).isEqualTo(manifestKey);
    assertThat(mutationKey.getName())
        .isEqualTo(KeyFactory.keyToString(Key.create(someObject).getRaw()));
  }

  @Test
  void test_create_createsExpectedMutation() {
    Entity rawEntity = convertToEntityInTxn(someObject);
    // Needs to be in a transaction so that registry-saving-to-entity will work.
    CommitLogMutation mutation =
        tm().transact(() -> CommitLogMutation.create(manifestKey, someObject));
    assertThat(Key.create(mutation))
        .isEqualTo(CommitLogMutation.createKey(manifestKey, Key.create(someObject)));
    assertThat(mutation.getEntity()).isEqualTo(rawEntity);
    assertThat(EntityTranslator.createFromPbBytes(mutation.getEntityProtoBytes()))
        .isEqualTo(rawEntity);
  }

  @Test
  void test_createRaw_createsExpectedMutation() {
    Entity rawEntity = convertToEntityInTxn(someObject);
    CommitLogMutation mutation = CommitLogMutation.createFromRaw(manifestKey, rawEntity);
    assertThat(Key.create(mutation))
        .isEqualTo(CommitLogMutation.createKey(manifestKey, Key.create(someObject)));
    assertThat(mutation.getEntity()).isEqualTo(rawEntity);
    assertThat(EntityTranslator.createFromPbBytes(mutation.getEntityProtoBytes()))
        .isEqualTo(rawEntity);
  }

  private static Entity convertToEntityInTxn(final ImmutableObject object) {
    return tm().transact(() -> ofy().save().toEntity(object));
  }
}
