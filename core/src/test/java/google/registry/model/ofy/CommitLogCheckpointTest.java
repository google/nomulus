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
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CommitLogCheckpoint}. */
@RunWith(JUnit4.class)
public class CommitLogCheckpointTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private static final DateTime T1 = START_OF_TIME;
  private static final DateTime T2 = START_OF_TIME.plusMillis(1);
  private static final DateTime T3 = START_OF_TIME.plusMillis(2);

  @Test
  public void test_getCheckpointTime() {
    DateTime now = DateTime.now(UTC);
    CommitLogCheckpoint checkpoint =
        CommitLogCheckpoint.create(now, ImmutableMap.of(1, T1, 2, T2, 3, T3));
    assertThat(checkpoint.getCheckpointTime()).isEqualTo(now);
  }

  @Test
  public void test_getBucketTimestamps() {
    CommitLogCheckpoint checkpoint =
        CommitLogCheckpoint.create(DateTime.now(UTC), ImmutableMap.of(1, T1, 2, T2, 3, T3));
    assertThat(checkpoint.getBucketTimestamps()).containsExactly(1, T1, 2, T2, 3, T3);
  }

  @Test
  public void test_create_notEnoughBucketTimestamps_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> CommitLogCheckpoint.create(DateTime.now(UTC), ImmutableMap.of(1, T1, 2, T2)));
    assertThat(thrown).hasMessageThat().contains("Bucket ids are incorrect");
  }

  @Test
  public void test_create_tooManyBucketTimestamps_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CommitLogCheckpoint.create(
                    DateTime.now(UTC), ImmutableMap.of(1, T1, 2, T2, 3, T3, 4, T1)));
    assertThat(thrown).hasMessageThat().contains("Bucket ids are incorrect");
  }

  @Test
  public void test_create_wrongBucketIds_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CommitLogCheckpoint.create(
                    DateTime.now(UTC), ImmutableMap.of(0, T1, 1, T2, 2, T3)));
    assertThat(thrown).hasMessageThat().contains("Bucket ids are incorrect");
  }

  @Test
  public void test_create_wrongBucketIdOrder_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CommitLogCheckpoint.create(
                    DateTime.now(UTC), ImmutableMap.of(2, T2, 1, T1, 3, T3)));
    assertThat(thrown).hasMessageThat().contains("Bucket ids are incorrect");
  }
}
