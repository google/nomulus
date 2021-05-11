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

package google.registry.testing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.ReplayQueue;
import google.registry.model.ofy.TransactionInfo;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit extension that replays datastore transactions against postgresql.
 *
 * <p>This extension must be ordered before AppEngineExtension so that the test entities saved in
 * that extension are also replayed. If AppEngineExtension is not used,
 * JpaTransactionManagerExtension must be, and this extension should be ordered _after_
 * JpaTransactionManagerExtension so that writes to SQL work.
 *
 * <p>If the "compare" flag is set in the constructor, this will also compare all touched objects in
 * both databases after performing the replay.
 */
public class ReplayExtension implements BeforeEachCallback, AfterEachCallback {

  FakeClock clock;
  boolean compare;
  InjectExtension injectExtension = new InjectExtension();

  private ReplayExtension(FakeClock clock, boolean compare) {
    this.clock = clock;
    this.compare = compare;
  }

  public static ReplayExtension createWithCompare(FakeClock clock) {
    return new ReplayExtension(clock, true);
  }

  public static ReplayExtension createWithoutCompare(FakeClock clock) {
    return new ReplayExtension(clock, false);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // Use a single bucket to expose timestamp inversion problems. This typically happens when
    // a test with this extension rolls back the fake clock in the setup method, creating inverted
    // timestamp with the canned data preloaded by AppengineExtension. The solution is to move
    // the clock change to the test's constructor.
    injectExtension.setStaticField(
        CommitLogBucket.class, "bucketIdSupplier", Suppliers.ofInstance(1));
    DatabaseHelper.setClock(clock);
    DatabaseHelper.setAlwaysSaveWithBackup(true);
    ReplayQueue.clear();
    context.getStore(ExtensionContext.Namespace.GLOBAL).put(ReplayExtension.class, this);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // This ensures that we do the replay even if we're not called from AppEngineExtension.  It
    // should be safe to call replayToSql() twice, as the replay queue should be empty the second
    // time.
    replayToSql();
    injectExtension.afterEach(context);
  }

  private static ImmutableSet<String> NON_REPLICATED_TYPES =
      ImmutableSet.of(
          "PremiumList",
          "PremiumListRevision",
          "PremiumListEntry",
          "ReservedList",
          "RdeRevision",
          "KmsSecretRevision",
          "ServerSecret",
          "SignedMarkRevocationList",
          "ClaimsListShard",
          "TmchCrl",
          "EppResourceIndex",
          "ForeignKeyIndex",
          "ForeignKeyHostIndex",
          "ForeignKeyContactIndex",
          "ForeignKeyDomainIndex");

  public void replayToSql() {
    DatabaseHelper.setAlwaysSaveWithBackup(false);
    ImmutableMap<Key<?>, Object> changes = ReplayQueue.replay();

    // Compare JPA to OFY, if requested.
    if (compare) {
      for (ImmutableMap.Entry<Key<?>, Object> entry : changes.entrySet()) {
        // Don't verify non-replicated types.
        if (NON_REPLICATED_TYPES.contains(entry.getKey().getKind())) {
          continue;
        }

        // Since the object may have changed in datastore by the time we're doing the replay, we
        // have to compare the current value in SQL (which we just mutated) against the value that
        // we originally would have persisted (that being the object in the entry).
        VKey<?> vkey = VKey.from(entry.getKey());
        jpaTm()
            .transact(
                () -> {
                  Optional<?> jpaValue = jpaTm().loadByKeyIfPresent(vkey);
                  if (entry.getValue().equals(TransactionInfo.Delete.SENTINEL)) {
                    assertThat(jpaValue.isPresent()).isFalse();
                  } else {
                    ImmutableObject immutJpaObject = (ImmutableObject) jpaValue.get();
                    assertAboutImmutableObjects().that(immutJpaObject).hasCorrectHashValue();
                    assertAboutImmutableObjects()
                        .that(immutJpaObject)
                        .isEqualAcrossDatabases(
                            (ImmutableObject)
                                ((DatastoreEntity) entry.getValue()).toSqlEntity().get());
                  }
                });
      }
    }
  }
}
