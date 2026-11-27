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
import static google.registry.bsa.BlockListType.BLOCK;
import static google.registry.bsa.BlockListType.BLOCK_PLUS;
import static google.registry.bsa.DownloadStage.APPLY_ORDER_AND_LABEL_DIFF;
import static google.registry.bsa.DownloadStage.CHECKSUMS_DO_NOT_MATCH;
import static google.registry.bsa.DownloadStage.DONE;
import static google.registry.bsa.DownloadStage.DOWNLOAD_BLOCK_LISTS;
import static google.registry.bsa.DownloadStage.MAKE_ORDER_AND_LABEL_DIFF;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.bsa.DownloadStage.REPORT_END_OF_ORDER_PROCESSING;
import static google.registry.bsa.DownloadStage.REPORT_START_OF_ORDER_PROCESSING;
import static google.registry.bsa.DownloadStage.UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockListType;
import google.registry.bsa.DownloadStage;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DownloadSchedule} stage transitions. */
class DownloadScheduleTest {

  private static final Instant START_TIME = Instant.parse("2024-01-01T00:00:00Z");
  private static final ImmutableMap<BlockListType, String> CHECKSUMS =
      ImmutableMap.of(BLOCK, "hash1", BLOCK_PLUS, "hash2");
  private static final CompletedJob PREV_COMPLETED =
      new CompletedJob(
          "2023-12-31t000000.000z", ImmutableMap.of(BLOCK, "old1", BLOCK_PLUS, "old2"));

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private DownloadSchedule persistSchedule(DownloadStage initialStage, boolean alwaysDownload) {
    BsaDownload download = new BsaDownload().setStage(initialStage);
    tm().transact(() -> tm().insert(download));
    return DownloadSchedule.create(download, PREV_COMPLETED, alwaysDownload);
  }

  @Test
  void updateJobStage_fullHappyPath_preservesLatestCompletedAndAlwaysDownload() {
    DownloadSchedule schedule = persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ false);

    schedule = schedule.updateJobStage(MAKE_ORDER_AND_LABEL_DIFF, CHECKSUMS);
    assertThat(schedule.stage()).isEqualTo(MAKE_ORDER_AND_LABEL_DIFF);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);
    assertThat(schedule.alwaysDownload()).isFalse();

    schedule = schedule.updateJobStage(APPLY_ORDER_AND_LABEL_DIFF);
    assertThat(schedule.stage()).isEqualTo(APPLY_ORDER_AND_LABEL_DIFF);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);
    assertThat(schedule.alwaysDownload()).isFalse();

    schedule = schedule.updateJobStage(REPORT_START_OF_ORDER_PROCESSING);
    assertThat(schedule.stage()).isEqualTo(REPORT_START_OF_ORDER_PROCESSING);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);

    schedule = schedule.updateJobStage(UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS);
    assertThat(schedule.stage()).isEqualTo(UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);

    schedule = schedule.updateJobStage(REPORT_END_OF_ORDER_PROCESSING);
    assertThat(schedule.stage()).isEqualTo(REPORT_END_OF_ORDER_PROCESSING);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);

    schedule = schedule.updateJobStage(DONE);
    assertThat(schedule.stage()).isEqualTo(DONE);
    assertThat(schedule.latestCompleted()).hasValue(PREV_COMPLETED);
    assertThat(schedule.alwaysDownload()).isFalse();

    long jobId = schedule.jobId();
    BsaDownload persisted = tm().transact(() -> tm().loadByKey(BsaDownload.vKey(jobId)));
    assertThat(persisted.getStage()).isEqualTo(DONE);
    assertThat(persisted.getChecksums()).isEqualTo(CHECKSUMS);
  }

  @Test
  void updateJobStage_terminalDownloadBranches_preserveMetadata() {
    DownloadSchedule nopSchedule =
        persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ false)
            .updateJobStage(NOP, CHECKSUMS);
    assertThat(nopSchedule.stage()).isEqualTo(NOP);
    assertThat(nopSchedule.latestCompleted()).hasValue(PREV_COMPLETED);
    assertThat(nopSchedule.alwaysDownload()).isFalse();

    fakeClock.advanceOneMilli();
    DownloadSchedule mismatchSchedule =
        persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ true)
            .updateJobStage(CHECKSUMS_DO_NOT_MATCH, CHECKSUMS);
    assertThat(mismatchSchedule.stage()).isEqualTo(CHECKSUMS_DO_NOT_MATCH);
    assertThat(mismatchSchedule.latestCompleted()).hasValue(PREV_COMPLETED);
    assertThat(mismatchSchedule.alwaysDownload()).isTrue();
  }

  @Test
  void updateJobStage_doneToNop_rejectedWithOrderedMessage() {
    DownloadSchedule schedule = persistSchedule(DONE, /* alwaysDownload= */ false);
    VerifyException thrown =
        assertThrows(VerifyException.class, () -> schedule.updateJobStage(NOP));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [NOP]. Cannot transition from [DONE]");
  }

  @Test
  void updateJobStage_doneToChecksumsDoNotMatch_rejected() {
    DownloadSchedule schedule = persistSchedule(DONE, /* alwaysDownload= */ false);
    VerifyException thrown =
        assertThrows(VerifyException.class, () -> schedule.updateJobStage(CHECKSUMS_DO_NOT_MATCH));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [CHECKSUMS_DO_NOT_MATCH]. Cannot transition from [DONE]");
  }

  @Test
  void updateJobStage_nopToChecksumsDoNotMatch_rejected() {
    DownloadSchedule schedule = persistSchedule(NOP, /* alwaysDownload= */ false);
    VerifyException thrown =
        assertThrows(VerifyException.class, () -> schedule.updateJobStage(CHECKSUMS_DO_NOT_MATCH));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [CHECKSUMS_DO_NOT_MATCH]. Cannot transition from [NOP]");
  }

  @Test
  void updateJobStage_stageSkipsAndBackwardTransitions_rejected() {
    DownloadSchedule downloadSchedule =
        persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ false);
    VerifyException skipFromDownload =
        assertThrows(VerifyException.class, () -> downloadSchedule.updateJobStage(DONE));
    assertThat(skipFromDownload)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [DONE]. Cannot transition from [DOWNLOAD_BLOCK_LISTS]");

    fakeClock.advanceOneMilli();
    DownloadSchedule diffSchedule =
        persistSchedule(MAKE_ORDER_AND_LABEL_DIFF, /* alwaysDownload= */ false);
    VerifyException skipFromDiff =
        assertThrows(
            VerifyException.class,
            () -> diffSchedule.updateJobStage(REPORT_START_OF_ORDER_PROCESSING));
    assertThat(skipFromDiff)
        .hasMessageThat()
        .isEqualTo(
            "Invalid new stage [REPORT_START_OF_ORDER_PROCESSING]. Cannot transition from"
                + " [MAKE_ORDER_AND_LABEL_DIFF]");

    VerifyException selfTransition =
        assertThrows(
            VerifyException.class, () -> diffSchedule.updateJobStage(MAKE_ORDER_AND_LABEL_DIFF));
    assertThat(selfTransition)
        .hasMessageThat()
        .isEqualTo(
            "Invalid new stage [MAKE_ORDER_AND_LABEL_DIFF]. Cannot transition from"
                + " [MAKE_ORDER_AND_LABEL_DIFF]");

    VerifyException backwardTransition =
        assertThrows(
            VerifyException.class, () -> diffSchedule.updateJobStage(DOWNLOAD_BLOCK_LISTS));
    assertThat(backwardTransition)
        .hasMessageThat()
        .isEqualTo(
            "Invalid new stage [DOWNLOAD_BLOCK_LISTS]. Cannot transition from"
                + " [MAKE_ORDER_AND_LABEL_DIFF]");
  }

  @Test
  void updateJobStage_leavingDownloadWithoutChecksums_rejected() {
    DownloadSchedule schedule = persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ false);
    VerifyException thrown =
        assertThrows(
            VerifyException.class, () -> schedule.updateJobStage(MAKE_ORDER_AND_LABEL_DIFF));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide checksums when leaving [DOWNLOAD_BLOCK_LISTS] stage");
  }

  @Test
  void updateJobStageWithChecksums_invalidTargetOrCurrentStage_rejected() {
    DownloadSchedule downloadSchedule =
        persistSchedule(DOWNLOAD_BLOCK_LISTS, /* alwaysDownload= */ false);
    IllegalArgumentException invalidTarget =
        assertThrows(
            IllegalArgumentException.class, () -> downloadSchedule.updateJobStage(DONE, CHECKSUMS));
    assertThat(invalidTarget)
        .hasMessageThat()
        .isEqualTo("Invalid new stage [DONE]. Cannot transition from [DOWNLOAD_BLOCK_LISTS]");

    fakeClock.advanceOneMilli();
    DownloadSchedule diffSchedule =
        persistSchedule(MAKE_ORDER_AND_LABEL_DIFF, /* alwaysDownload= */ false);
    VerifyException invalidCurrentStage =
        assertThrows(
            VerifyException.class,
            () -> diffSchedule.updateJobStage(MAKE_ORDER_AND_LABEL_DIFF, CHECKSUMS));
    assertThat(invalidCurrentStage)
        .hasMessageThat()
        .isEqualTo(
            "Invalid invocation: may only update checksums during [DOWNLOAD_BLOCK_LISTS] stage,"
                + " found [MAKE_ORDER_AND_LABEL_DIFF]");
  }
}
