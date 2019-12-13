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

package google.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.RDE_REPORT;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import google.registry.model.common.Cursor;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.model.transaction.TransactionManager;
import google.registry.schema.cursor.CursorDao;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CommitLogCheckpointStrategy}. */
@RunWith(JUnit4.class)
public class CommitLogCheckpointStrategyTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  final Ofy ofy = new Ofy(clock);
  final TransactionManager tm = new DatastoreTransactionManager(ofy);
  final CommitLogCheckpointStrategy strategy = new CommitLogCheckpointStrategy();

  /**
   * Supplier to inject into CommitLogBucket for doling out predictable bucket IDs.
   *
   * <p>If not overridden, the supplier returns 1 so that other saves won't hit an NPE (since even
   * if they use saveWithoutBackup() the transaction still selects a bucket key early).
   */
  final FakeSupplier<Integer> fakeBucketIdSupplier = new FakeSupplier<>(1);

  /** Gross but necessary supplier that can be modified to return the desired value. */
  private static class FakeSupplier<T> implements Supplier<T> {
    /** Default value to return if 'value' is not set. */
    final T defaultValue;

    /** Set this value field to make the supplier return this value. */
    T value = null;

    public FakeSupplier(T defaultValue) {
      this.defaultValue = defaultValue;
    }

    @Override
    public T get() {
      return value == null ? defaultValue : value;
    }
  }

  @Before
  public void before() {
    strategy.clock = clock;
    strategy.ofy = ofy;

    // Need to inject clock into Ofy so that createTld() below will get the right time.
    inject.setStaticField(Ofy.class, "clock", clock);
    // Inject a fake bucket ID supplier so we can dole out specific bucket IDs to commit logs.
    inject.setStaticField(CommitLogBucket.class, "bucketIdSupplier", fakeBucketIdSupplier);

    // Create some fake TLDs to parent RegistryCursor test objects under.
    createTld("tld1");
    createTld("tld2");
    createTld("tld3");
    clock.advanceOneMilli();
  }

  @Test
  public void test_readBucketTimestamps_noCommitLogs() {
    assertThat(strategy.readBucketTimestamps())
        .containsExactly(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME);
  }

  @Test
  public void test_readBucketTimestamps_withSomeCommitLogs() {
    DateTime startTime = clock.nowUtc();
    writeCommitLogToBucket(1);
    clock.advanceOneMilli();
    writeCommitLogToBucket(2);
    assertThat(strategy.readBucketTimestamps())
        .containsExactly(1, startTime, 2, startTime.plusMillis(1), 3, START_OF_TIME);
  }

  @Test
  public void test_readBucketTimestamps_againAfterUpdate_reflectsUpdate() {
    DateTime firstTime = clock.nowUtc();
    writeCommitLogToBucket(1);
    writeCommitLogToBucket(2);
    writeCommitLogToBucket(3);
    assertThat(strategy.readBucketTimestamps().values())
        .containsExactly(firstTime, firstTime, firstTime);
    clock.advanceOneMilli();
    writeCommitLogToBucket(1);
    DateTime secondTime = clock.nowUtc();
    assertThat(strategy.readBucketTimestamps())
        .containsExactly(1, secondTime, 2, firstTime, 3, firstTime);
  }

  @Test
  public void test_readNewCommitLogsAndFindThreshold_noCommitsAtAll_returnsEndOfTime() {
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME);
    assertThat(strategy.readNewCommitLogsAndFindThreshold(bucketTimes)).isEqualTo(END_OF_TIME);
  }

  @Test
  public void test_readNewCommitLogsAndFindThreshold_noNewCommits_returnsEndOfTime() {
    DateTime now = clock.nowUtc();
    writeCommitLogToBucket(1);
    clock.advanceOneMilli();
    writeCommitLogToBucket(2);
    clock.advanceOneMilli();
    writeCommitLogToBucket(3);
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, now, 2, now.plusMillis(1), 3, now.plusMillis(2));
    assertThat(strategy.readNewCommitLogsAndFindThreshold(bucketTimes)).isEqualTo(END_OF_TIME);
  }

  @Test
  public void test_readNewCommitLogsAndFindThreshold_tiedNewCommits_returnsCommitTimeMinusOne() {
    DateTime now = clock.nowUtc();
    writeCommitLogToBucket(1);
    writeCommitLogToBucket(2);
    writeCommitLogToBucket(3);
    assertThat(strategy.readNewCommitLogsAndFindThreshold(
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)))
            .isEqualTo(now.minusMillis(1));
  }

  @Test
  public void test_readNewCommitLogsAndFindThreshold_someNewCommits_returnsEarliestTimeMinusOne() {
    DateTime now = clock.nowUtc();
    writeCommitLogToBucket(1);  // 1A
    writeCommitLogToBucket(2);  // 2A
    writeCommitLogToBucket(3);  // 3A
    clock.advanceBy(Duration.millis(5));
    writeCommitLogToBucket(1);  // 1B
    writeCommitLogToBucket(2);  // 2B
    writeCommitLogToBucket(3);  // 3B
    clock.advanceBy(Duration.millis(5));
    writeCommitLogToBucket(1);  // 1C
    writeCommitLogToBucket(2);  // 2C
    writeCommitLogToBucket(3);  // 3C
    // First pass times: 1 at T0, 2 at T+5, 3 at T+10.
    // Commits 1A, 2B, 3C are the commits seen in the first pass.
    // Commits 2A, 3A, 3B are all old prior commits that should be ignored.
    // Commit 1B is the first new commit for bucket 1, at T+5.
    // Commit 1C is the second new commit for bucket 1, at T+10, and should be ignored.
    // Commit 2C is the first new commit for bucket 2, at T+10.
    // Since 1B as a new commit is older than 1C, T+5 is the oldest new commit time.
    // Therefore, expect T+4 as the threshold time.
    assertThat(strategy.readNewCommitLogsAndFindThreshold(
        ImmutableMap.of(1, now, 2, now.plusMillis(5), 3, now.plusMillis(10))))
            .isEqualTo(now.plusMillis(4));
  }

  @Test
  public void test_readNewCommitLogsAndFindThreshold_commitsAtBucketTimes() {
    DateTime now = clock.nowUtc();
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, now.minusMillis(1), 2, now, 3, now.plusMillis(1));
    assertThat(strategy.readNewCommitLogsAndFindThreshold(bucketTimes)).isEqualTo(END_OF_TIME);
  }

  @Test
  public void test_computeBucketCheckpointTimes_earlyThreshold_setsEverythingToThreshold() {
    DateTime now = clock.nowUtc();
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, now.minusMillis(1), 2, now, 3, now.plusMillis(1));
    assertThat(strategy.computeBucketCheckpointTimes(bucketTimes, now.minusMillis(2)).values())
        .containsExactly(now.minusMillis(2), now.minusMillis(2), now.minusMillis(2));
  }

  @Test
  public void test_computeBucketCheckpointTimes_middleThreshold_clampsToThreshold() {
    DateTime now = clock.nowUtc();
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, now.minusMillis(1), 2, now, 3, now.plusMillis(1));
    assertThat(strategy.computeBucketCheckpointTimes(bucketTimes, now))
        .containsExactly(1, now.minusMillis(1), 2, now, 3, now);
  }

  @Test
  public void test_computeBucketCheckpointTimes_lateThreshold_leavesBucketTimesAsIs() {
    DateTime now = clock.nowUtc();
    ImmutableMap<Integer, DateTime> bucketTimes =
        ImmutableMap.of(1, now.minusMillis(1), 2, now, 3, now.plusMillis(1));
    assertThat(strategy.computeBucketCheckpointTimes(bucketTimes, now.plusMillis(2)))
        .isEqualTo(bucketTimes);
  }

  @Test
  public void test_computeCheckpoint_noCommitsAtAll_bucketCheckpointTimesAreStartOfTime() {
    assertThat(strategy.computeCheckpoint())
        .isEqualTo(CommitLogCheckpoint.create(
            clock.nowUtc(),
            ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));
  }

  @Test
  public void test_computeCheckpoint_noNewCommitLogs_bucketCheckpointTimesAreBucketTimes() {
    DateTime now = clock.nowUtc();
    writeCommitLogToBucket(1);
    clock.advanceOneMilli();
    writeCommitLogToBucket(2);
    clock.advanceOneMilli();
    writeCommitLogToBucket(3);
    clock.advanceOneMilli();
    DateTime checkpointTime = clock.nowUtc();
    assertThat(strategy.computeCheckpoint())
        .isEqualTo(CommitLogCheckpoint.create(
            checkpointTime,
            ImmutableMap.of(1, now, 2, now.plusMillis(1), 3, now.plusMillis(2))));
  }

  @Test
  public void test_computeCheckpoint_someNewCommits_bucketCheckpointTimesAreClampedToThreshold() {
    DateTime now = clock.nowUtc();
    writeCommitLogToBucket(1);  // 1A
    writeCommitLogToBucket(2);  // 2A
    writeCommitLogToBucket(3);  // 3A
    clock.advanceBy(Duration.millis(5));
    writeCommitLogToBucket(1);  // 1B
    writeCommitLogToBucket(2);  // 2B
    writeCommitLogToBucket(3);  // 3B
    clock.advanceBy(Duration.millis(5));
    writeCommitLogToBucket(1);  // 1C
    writeCommitLogToBucket(2);  // 2C
    writeCommitLogToBucket(3);  // 3C

    // Set first pass times: 1 at T0, 2 at T+5, 3 at T+10.
    saveBucketWithLastWrittenTime(1, now);
    saveBucketWithLastWrittenTime(2, now.plusMillis(5));
    saveBucketWithLastWrittenTime(3, now.plusMillis(10));

    // Commits 1A, 2B, 3C are the commits seen in the first pass.
    // Commits 2A, 3A, 3B are all old prior commits that should be ignored.
    // Commit 1B is the first new commit for bucket 1, at T+5.
    // Commit 1C is the second new commit for bucket 1, at T+10, and should be ignored.
    // Commit 2C is the first new commit for bucket 2, at T+10.
    // Since 1B as a new commit is older than 1C, T+5 is the oldest new commit time.
    // Therefore, expect T+4 as the threshold time.
    DateTime threshold = now.plusMillis(4);

    // Advance clock before taking checkpoint.
    clock.advanceBy(Duration.millis(10));
    DateTime checkpointTime = clock.nowUtc();

    // Bucket checkpoint times should be clamped as expected.
    assertThat(strategy.computeCheckpoint())
        .isEqualTo(CommitLogCheckpoint.create(
            checkpointTime,
            ImmutableMap.of(1, now, 2, threshold, 3, threshold)));
  }

  private void writeCommitLogToBucket(final int bucketId) {
    fakeBucketIdSupplier.value = bucketId;
    tm.transact(
        () ->
            CursorDao.saveCursor(
                Cursor.create(RDE_REPORT, tm.getTransactionTime(), Registry.get("tld" + bucketId)),
                "tld" + bucketId));
    fakeBucketIdSupplier.value = null;
  }

  private void saveBucketWithLastWrittenTime(final int bucketId, final DateTime lastWrittenTime) {
    tm.transact(
        () ->
            ofy.saveWithoutBackup()
                .entity(
                    CommitLogBucket.loadBucket(getBucketKey(bucketId))
                        .asBuilder()
                        .setLastWrittenTime(lastWrittenTime)
                        .build()));
  }
}
