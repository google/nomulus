// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.bsa.RefreshStage.APPLY_CHANGES;
import static google.registry.bsa.RefreshStage.DONE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.bsa.DownloadStage;
import google.registry.bsa.RefreshStage;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
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
    scheduler = new RefreshScheduler();
  }

  @Test
  void schedule_noPrevRefresh_noPrevDownload() {
    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isEmpty();
  }

  @Test
  void schedule_noPrevRefresh_withOngoingPrevDownload() {
    tm().transact(() -> tm().insert(new BsaDownload()));
    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isEmpty();
  }

  @Test
  void schedule_NoPreviousRefresh_withCompletedPrevDownload() {
    tm().transact(() -> tm().insert(new BsaDownload().setStage(DownloadStage.DONE)));
    DateTime downloadTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();

    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    RefreshSchedule schedule = scheduleOptional.get();

    assertThat(schedule.jobCreationTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(schedule.stage()).isEqualTo(RefreshStage.CHECK_FOR_CHANGES);
    assertThat(schedule.prevRefreshTime()).isEqualTo(downloadTime);
  }

  @Test
  void schedule_firstRefreshOngoing() {
    tm().transact(() -> tm().insert(new BsaDownload().setStage(DownloadStage.DONE)));
    DateTime downloadTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();

    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(APPLY_CHANGES)));
    DateTime refreshStartTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();

    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    RefreshSchedule schedule = scheduleOptional.get();

    assertThat(schedule.jobCreationTime()).isEqualTo(refreshStartTime);
    assertThat(schedule.stage()).isEqualTo(APPLY_CHANGES);
    assertThat(schedule.prevRefreshTime()).isEqualTo(downloadTime);
  }

  @Test
  void schedule_firstRefreshDone() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(DONE)));
    DateTime prevRefreshStartTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();

    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    RefreshSchedule schedule = scheduleOptional.get();

    assertThat(schedule.jobCreationTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(schedule.stage()).isEqualTo(RefreshStage.CHECK_FOR_CHANGES);
    assertThat(schedule.prevRefreshTime()).isEqualTo(prevRefreshStartTime);
  }

  @Test
  void schedule_ongoingRefreshWithPrevCompletion() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(DONE)));
    DateTime prevRefreshStartTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(APPLY_CHANGES)));
    DateTime ongoingRefreshStartTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();

    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isPresent();
    RefreshSchedule schedule = scheduleOptional.get();

    assertThat(schedule.jobCreationTime()).isEqualTo(ongoingRefreshStartTime);
    assertThat(schedule.stage()).isEqualTo(APPLY_CHANGES);
    assertThat(schedule.prevRefreshTime()).isEqualTo(prevRefreshStartTime);
  }

  @Test
  void schedule_blockedByOngoingDownload() {
    tm().transact(() -> tm().insert(new BsaDomainRefresh().setStage(DONE)));
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(new BsaDownload()));
    fakeClock.advanceOneMilli();

    Optional<RefreshSchedule> scheduleOptional = scheduler.schedule();
    assertThat(scheduleOptional).isEmpty();
  }
}
