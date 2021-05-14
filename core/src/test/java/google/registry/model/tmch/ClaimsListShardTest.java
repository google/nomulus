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

package google.registry.model.tmch;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.tmch.ClaimsListShard.ClaimsListRevision;
import google.registry.model.tmch.ClaimsListShard.UnshardedSaveException;
import google.registry.testing.AppEngineExtension;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ClaimsListShard}. */
public class ClaimsListShardTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final int shardSize = 10;

  @Test
  void test_unshardedSaveFails() {
    assertThrows(
        UnshardedSaveException.class,
        () ->
            tm().transact(
                    () -> {
                      ClaimsListShard claimsList =
                          ClaimsListShard.create(
                              tm().getTransactionTime(), ImmutableMap.of("a", "b"));
                      claimsList.id = 1; // Without an id this won't save anyways.
                      claimsList.parent = ClaimsListRevision.createKey();
                      auditedOfy().saveWithoutBackup().entity(claimsList).now();
                    }));
  }

  @Test
  void testGet_safelyLoadsEmptyClaimsList_whenNoShardsExist() {
    assertThat(ClaimsListShard.getFromDatastore()).isEmpty();
  }

  @Test
  void test_savesAndGets_withSharding() {
    // Create a ClaimsList that will need 4 shards to save.
    Map<String, String> labelsToKeys = new HashMap<>();
    for (int i = 0; i <= shardSize * 3; i++) {
      labelsToKeys.put(Integer.toString(i), Integer.toString(i));
    }
    DateTime now = DateTime.now(UTC);
    // Save it with sharding, and make sure that reloading it works.
    ClaimsListShard unsharded = ClaimsListShard.create(now, ImmutableMap.copyOf(labelsToKeys));
    unsharded.saveToDatastore(shardSize);
    assertThat(ClaimsListShard.getFromDatastore().get().labelsToKeys)
        .isEqualTo(unsharded.labelsToKeys);
    List<ClaimsListShard> shards1 = auditedOfy().load().type(ClaimsListShard.class).list();
    assertThat(shards1).hasSize(4);
    assertThat(ClaimsListShard.getFromDatastore().get().getClaimKey("1")).hasValue("1");
    assertThat(ClaimsListShard.getFromDatastore().get().getClaimKey("a")).isEmpty();
    assertThat(ClaimsListShard.getCurrentRevision()).isEqualTo(shards1.get(0).parent);

    // Create a smaller ClaimsList that will need only 2 shards to save.
    labelsToKeys = new HashMap<>();
    for (int i = 0; i <= shardSize; i++) {
      labelsToKeys.put(Integer.toString(i), Integer.toString(i));
    }
    unsharded = ClaimsListShard.create(now.plusDays(1), ImmutableMap.copyOf(labelsToKeys));
    unsharded.saveToDatastore(shardSize);
    auditedOfy().clearSessionCache();
    assertThat(ClaimsListShard.getFromDatastore().get().labelsToKeys)
        .hasSize(unsharded.labelsToKeys.size());
    assertThat(ClaimsListShard.getFromDatastore().get().labelsToKeys)
        .isEqualTo(unsharded.labelsToKeys);
    List<ClaimsListShard> shards2 = auditedOfy().load().type(ClaimsListShard.class).list();
    assertThat(shards2).hasSize(2);

    // Expect that the old revision is deleted.
    assertThat(ClaimsListShard.getCurrentRevision()).isEqualTo(shards2.get(0).parent);
  }

  /**
   * Returns a created claims list shard with the specified parent key for testing purposes only.
   */
  public static ClaimsListShard createTestClaimsListShard(
      DateTime creationTime,
      ImmutableMap<String, String> labelsToKeys,
      Key<ClaimsListRevision> revision) {
    ClaimsListShard claimsList = ClaimsListShard.create(creationTime, labelsToKeys);
    claimsList.isShard = true;
    claimsList.parent = revision;
    return claimsList;
  }
}
