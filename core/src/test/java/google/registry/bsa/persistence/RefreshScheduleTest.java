// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.bsa.RefreshStage.CHECK_FOR_CHANGES;
import static google.registry.bsa.RefreshStage.DONE;
import static google.registry.bsa.RefreshStage.UPLOAD_ADDITIONS;
import static google.registry.bsa.RefreshStage.UPLOAD_REMOVALS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.VerifyException;
import google.registry.bsa.RefreshStage;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshSchedule} stage transitions. */
class RefreshScheduleTest {

  private static final Instant START_TIME = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant PREV_REFRESH_TIME = Instant.parse("2023-12-31T23:30:00Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private RefreshSchedule persistSchedule(RefreshStage initialStage) {
    BsaDomainRefresh refresh = new BsaDomainRefresh().setStage(initialStage);
    tm().transact(() -> tm().insert(refresh));
    return RefreshSchedule.create(refresh, PREV_REFRESH_TIME);
  }

  @Test
  void updateJobStage_fullHappyPath_succeeds() {
    RefreshSchedule schedule = persistSchedule(CHECK_FOR_CHANGES);

    schedule = schedule.updateJobStage(APPLY_CHANGES);
    assertThat(schedule.stage()).isEqualTo(APPLY_CHANGES);
    assertThat(schedule.prevRefreshTime()).isEqualTo(PREV_REFRESH_TIME);

    schedule = schedule.updateJobStage(UPLOAD_REMOVALS);
    assertThat(schedule.stage()).isEqualTo(UPLOAD_REMOVALS);
    assertThat(schedule.prevRefreshTime()).isEqualTo(PREV_REFRESH_TIME);

    schedule = schedule.updateJobStage(UPLOAD_ADDITIONS);
    assertThat(schedule.stage()).isEqualTo(UPLOAD_ADDITIONS);
    assertThat(schedule.prevRefreshTime()).isEqualTo(PREV_REFRESH_TIME);

    schedule = schedule.updateJobStage(DONE);
    assertThat(schedule.stage()).isEqualTo(DONE);
    assertThat(schedule.prevRefreshTime()).isEqualTo(PREV_REFRESH_TIME);

    long jobId = schedule.jobId();
    assertThat(tm().transact(() -> tm().loadByKey(BsaDomainRefresh.vKey(jobId))).getStage())
        .isEqualTo(DONE);
  }

  @Test
  void updateJobStage_noChangesShortcut_succeeds() {
    RefreshSchedule schedule = persistSchedule(CHECK_FOR_CHANGES).updateJobStage(DONE);
    assertThat(schedule.stage()).isEqualTo(DONE);
    assertThat(schedule.prevRefreshTime()).isEqualTo(PREV_REFRESH_TIME);
  }

  @Test
  void updateJobStage_stageSkips_rejectedWithOrderedMessage() {
    RefreshSchedule checkSchedule = persistSchedule(CHECK_FOR_CHANGES);
    VerifyException skipToRemovals =
        assertThrows(VerifyException.class, () -> checkSchedule.updateJobStage(UPLOAD_REMOVALS));
    assertThat(skipToRemovals)
        .hasMessageThat()
        .isEqualTo(
            "Invalid new stage [UPLOAD_REMOVALS]. Cannot transition from [CHECK_FOR_CHANGES]");

    fakeClock.advanceOneMilli();
    RefreshSchedule applySchedule = persistSchedule(APPLY_CHANGES);
    VerifyException skipToDone =
        assertThrows(VerifyException.class, () -> applySchedule.updateJobStage(DONE));
    assertThat(skipToDone)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [DONE]. Cannot transition from [APPLY_CHANGES]");

    fakeClock.advanceOneMilli();
    RefreshSchedule removalsSchedule = persistSchedule(UPLOAD_REMOVALS);
    VerifyException skipAdditions =
        assertThrows(VerifyException.class, () -> removalsSchedule.updateJobStage(DONE));
    assertThat(skipAdditions)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [DONE]. Cannot transition from [UPLOAD_REMOVALS]");
  }

  @Test
  void updateJobStage_selfAndBackwardTransitions_rejected() {
    RefreshSchedule doneSchedule = persistSchedule(DONE);
    VerifyException backward =
        assertThrows(VerifyException.class, () -> doneSchedule.updateJobStage(CHECK_FOR_CHANGES));
    assertThat(backward)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [CHECK_FOR_CHANGES]. Cannot transition from [DONE]");

    VerifyException self =
        assertThrows(VerifyException.class, () -> doneSchedule.updateJobStage(DONE));
    assertThat(self)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [DONE]. Cannot transition from [DONE]");
  }
}
