// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.bsa.persistence.BsaDomainRefresh.Stage;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshScheduler}. */
public class RefreshSchedulerTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  RefreshScheduler scheduler;

  @BeforeEach
  void setup() {
    scheduler = new RefreshScheduler(fakeClock);
  }

  @Test
  void schedule_firstJobEver() {
    RefreshSchedule schedule = scheduler.schedule();
    assertThat(schedule.jobCreationTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(schedule.stage()).isEqualTo(Stage.MAKE_DIFF);
    assertThat(schedule.prevJobCreationTime()).isEmpty();
  }

  @Test
  void schedule_latestJobComplete() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(Stage.DONE)));
    DateTime latestCompletedJobTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    RefreshSchedule schedule = scheduler.schedule();
    assertThat(schedule.jobCreationTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(schedule.stage()).isEqualTo(Stage.MAKE_DIFF);
    assertThat(schedule.prevJobCreationTime()).hasValue(latestCompletedJobTime);
  }

  @Test
  void schedule_firstEverJobIncomplete() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(Stage.APPLY_DIFF)));
    DateTime ongoingJobTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    RefreshSchedule schedule = scheduler.schedule();
    assertThat(schedule.jobCreationTime()).isEqualTo(ongoingJobTime);
    assertThat(schedule.stage()).isEqualTo(Stage.APPLY_DIFF);
    assertThat(schedule.prevJobCreationTime()).isEmpty();
  }

  @Test
  void schedule_incompleteJobAfterCompletedOnes() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(Stage.DONE)));
    DateTime latestCompletedJobTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(Stage.APPLY_DIFF)));
    DateTime ongoingJobTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    RefreshSchedule schedule = scheduler.schedule();
    assertThat(schedule.jobCreationTime()).isEqualTo(ongoingJobTime);
    assertThat(schedule.stage()).isEqualTo(Stage.APPLY_DIFF);
    assertThat(schedule.prevJobCreationTime()).hasValue(latestCompletedJobTime);
  }
}
