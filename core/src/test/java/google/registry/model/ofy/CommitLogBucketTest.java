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
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.CommitLogBucket.loadAllBuckets;
import static google.registry.model.ofy.CommitLogBucket.loadBucket;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.annotation.Cache;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.InjectExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CommitLogBucket}. */
public class CommitLogBucketTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private CommitLogBucket bucket;

  @BeforeEach
  void before() {
    // Save the bucket with some non-default properties set so that we can distinguish a correct
    // load from one that returns a newly created bucket instance.
    bucket = persistResource(
        new CommitLogBucket.Builder()
            .setLastWrittenTime(END_OF_TIME)
            .setBucketNum(1)
            .build());
  }

  @Test
  void test_getBucketKey_createsBucketKeyInDefaultNamespace() {
    // Key.getNamespace() returns the empty string for the default namespace, not null.
    assertThat(getBucketKey(1).getRaw().getNamespace()).isEmpty();
  }

  @Test
  void test_getBucketKey_bucketNumberTooLow_throws() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> getBucketKey(0));
    assertThat(thrown).hasMessageThat().contains("0 not in [");
  }

  @Test
  void test_getBucketKey_bucketNumberTooHigh_throws() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> getBucketKey(11));
    assertThat(thrown).hasMessageThat().contains("11 not in [");
  }

  @Test
  void test_getArbitraryBucketId_withSupplierOverridden() {
    inject.setStaticField(
        CommitLogBucket.class, "bucketIdSupplier", Suppliers.ofInstance(4));  // xkcd.com/221
    // Try multiple times just in case it's actually still random.  If it is, the probability of
    // this test passing is googol^-1, so I think we're pretty safe.
    for (int i = 0; i < 100; i++) {
      assertThat(CommitLogBucket.getArbitraryBucketId()).isEqualTo(4);
    }
  }

  @Test
  void test_loadBucket_loadsTheBucket() {
    assertThat(loadBucket(getBucketKey(1))).isEqualTo(bucket);
  }

  @Test
  void test_loadBucket_forNonexistentBucket_returnsNewBucket() {
    assertThat(loadBucket(getBucketKey(3))).isEqualTo(
        new CommitLogBucket.Builder().setBucketNum(3).build());
  }

  @Test
  void test_loadAllBuckets_loadsExistingBuckets_orNewOnesIfNonexistent() {
    ImmutableSet<CommitLogBucket> buckets = loadAllBuckets();
    assertThat(buckets).hasSize(3);
    assertThat(buckets).contains(bucket);
    assertThat(buckets).contains(new CommitLogBucket.Builder().setBucketNum(2).build());
    assertThat(buckets).contains(new CommitLogBucket.Builder().setBucketNum(3).build());
  }

  @Test
  void test_noCacheAnnotation() {
    // Don't ever put @Cache on CommitLogBucket; it could mess up the checkpointing algorithm.
    assertThat(CommitLogBucket.class.isAnnotationPresent(Cache.class)).isFalse();
  }
}
