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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import google.registry.bsa.BlockListType;
import google.registry.bsa.DownloadStage;
import java.time.Instant;
import java.util.Optional;

/**
 * Information needed when handling a download from BSA.
 *
 * @param latestCompleted The most recent job that ended in the {@code DONE} stage.
 * @param alwaysDownload Whether the download should be processed even if the checksums show that it
 *     has not changed from the previous one.
 */
public record DownloadSchedule(
    long jobId,
    Instant jobCreationTime,
    String jobName,
    DownloadStage stage,
    Optional<CompletedJob> latestCompleted,
    boolean alwaysDownload) {

  static final ImmutableMultimap<DownloadStage, DownloadStage> ALLOWED_TRANSITIONS =
      ImmutableMultimap.<DownloadStage, DownloadStage>builder()
          .put(DOWNLOAD_BLOCK_LISTS, MAKE_ORDER_AND_LABEL_DIFF)
          .put(DOWNLOAD_BLOCK_LISTS, NOP)
          .put(DOWNLOAD_BLOCK_LISTS, CHECKSUMS_DO_NOT_MATCH)
          .put(MAKE_ORDER_AND_LABEL_DIFF, APPLY_ORDER_AND_LABEL_DIFF)
          .put(APPLY_ORDER_AND_LABEL_DIFF, REPORT_START_OF_ORDER_PROCESSING)
          .put(REPORT_START_OF_ORDER_PROCESSING, UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS)
          .put(UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS, REPORT_END_OF_ORDER_PROCESSING)
          .put(REPORT_END_OF_ORDER_PROCESSING, DONE)
          .build();

  /** Updates the current job to the new stage. */
  @CanIgnoreReturnValue
  public DownloadSchedule updateJobStage(DownloadStage stage) {
    return tm().transact(
            () -> {
              BsaDownload bsaDownload = tm().loadByKey(BsaDownload.vKey(jobId()));
              DownloadStage currentStage = bsaDownload.getStage();
              verify(
                  ALLOWED_TRANSITIONS.containsEntry(currentStage, stage),
                  "Invalid new stage [%s]. Cannot transition from [%s]",
                  stage,
                  currentStage);
              verify(
                  !currentStage.equals(DOWNLOAD_BLOCK_LISTS),
                  "Must provide checksums when leaving [%s] stage",
                  DOWNLOAD_BLOCK_LISTS);
              bsaDownload.setStage(stage);
              tm().put(bsaDownload);
              return create(bsaDownload, latestCompleted(), alwaysDownload());
            });
  }

  /**
   * Updates the current job to the new stage and sets the checksums of the downloaded files.
   *
   * <p>This method may only be invoked during the {@code DOWNLOAD_BLOCK_LISTS} stage, and the
   * target stage must be one of {@code MAKE_ORDER_AND_LABEL_DIFF}, {@code NOP}, or {@code
   * CHECKSUMS_DO_NOT_MATCH}.
   */
  @CanIgnoreReturnValue
  public DownloadSchedule updateJobStage(
      DownloadStage stage, ImmutableMap<BlockListType, String> checksums) {
    checkArgument(
        ALLOWED_TRANSITIONS.containsEntry(DOWNLOAD_BLOCK_LISTS, stage),
        "Invalid new stage [%s]. Cannot transition from [%s]",
        stage,
        DOWNLOAD_BLOCK_LISTS);
    return tm().transact(
            () -> {
              BsaDownload bsaDownload = tm().loadByKey(BsaDownload.vKey(jobId()));
              DownloadStage currentStage = bsaDownload.getStage();
              verify(
                  currentStage.equals(DOWNLOAD_BLOCK_LISTS),
                  "Invalid invocation: may only update checksums during [%s] stage, found [%s]",
                  DOWNLOAD_BLOCK_LISTS,
                  currentStage);
              bsaDownload.setStage(stage);
              bsaDownload.setChecksums(checksums);
              tm().put(bsaDownload);
              return create(bsaDownload, latestCompleted(), alwaysDownload());
            });
  }

  static DownloadSchedule create(BsaDownload currentJob) {
    return create(currentJob, Optional.empty(), /* alwaysDownload= */ true);
  }

  static DownloadSchedule create(
      BsaDownload currentJob, CompletedJob latestCompleted, boolean alwaysDownload) {
    return create(currentJob, Optional.of(latestCompleted), alwaysDownload);
  }

  private static DownloadSchedule create(
      BsaDownload currentJob, Optional<CompletedJob> latestCompleted, boolean alwaysDownload) {
    return new DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getCreationTime(),
        currentJob.getJobName(),
        currentJob.getStage(),
        latestCompleted,
        alwaysDownload);
  }

  /** Information about a completed BSA download job. */
  public record CompletedJob(String jobName, ImmutableMap<BlockListType, String> checksums) {

    public static CompletedJob create(BsaDownload completedJob) {
      return new CompletedJob(completedJob.getJobName(), completedJob.getChecksums());
    }
  }
}
