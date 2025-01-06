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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static google.registry.bsa.DownloadStage.CHECKSUMS_DO_NOT_MATCH;
import static google.registry.bsa.DownloadStage.MAKE_ORDER_AND_LABEL_DIFF;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockListType;
import google.registry.bsa.DownloadStage;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Information needed when handling a download from BSA.
 *
 * @param latestCompleted The most recent job that ended in the {@code DONE} stage.
 * @param alwaysDownload Whether the download should be processed even if the checksums show that it
 *     has not changed from the previous one.
 */
public record DownloadSchedule(
    long jobId,
    DateTime jobCreationTime,
    String jobName,
    DownloadStage stage,
    Optional<CompletedJob> latestCompleted,
    boolean alwaysDownload) {

  /** Updates the current job to the new stage. */
  public void updateJobStage(DownloadStage stage) {
    tm().transact(
            () -> {
              BsaDownload bsaDownload = tm().loadByKey(BsaDownload.vKey(jobId()));
              verify(
                  stage.compareTo(bsaDownload.getStage()) > 0,
                  "Invalid new stage [%s]. Must move forward from [%s]",
                  bsaDownload.getStage(),
                  stage);
              bsaDownload.setStage(stage);
              tm().put(bsaDownload);
            });
  }

  /**
   * Updates the current job to the new stage and sets the checksums of the downloaded files.
   *
   * <p>This method may only be invoked during the {@code DOWNLOAD} stage, and the target stage must
   * be one of {@code MAKE_DIFF}, {@code CHECK_FOR_STALE_UNBLOCKABLES}, {@code NOP}, or {@code
   * CHECKSUMS_NOT_MATCH}.
   */
  public DownloadSchedule updateJobStage(
      DownloadStage stage, ImmutableMap<BlockListType, String> checksums) {
    checkArgument(
        stage.equals(MAKE_ORDER_AND_LABEL_DIFF)
            || stage.equals(NOP)
            || stage.equals(CHECKSUMS_DO_NOT_MATCH),
        "Invalid stage [%s]",
        stage);
    return tm().transact(
            () -> {
              BsaDownload bsaDownload = tm().loadByKey(BsaDownload.vKey(jobId()));
              verify(
                  bsaDownload.getStage().equals(DownloadStage.DOWNLOAD_BLOCK_LISTS),
                  "Invalid invocation. May only invoke during the DOWNLOAD stage.");
              bsaDownload.setStage(stage);
              bsaDownload.setChecksums(checksums);
              tm().put(bsaDownload);
              return create(bsaDownload);
            });
  }

  static DownloadSchedule create(BsaDownload currentJob) {
    return new DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getCreationTime(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.empty(),
        /* alwaysDownload= */ true);
  }

  static DownloadSchedule create(
      BsaDownload currentJob, CompletedJob latestCompleted, boolean alwaysDownload) {
    return new DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getCreationTime(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.of(latestCompleted),
        /* alwaysDownload= */ alwaysDownload);
  }

  /** Information about a completed BSA download job. */
  public record CompletedJob(String jobName, ImmutableMap<BlockListType, String> checksums) {

    public static CompletedJob create(BsaDownload completedJob) {
      return new CompletedJob(completedJob.getJobName(), completedJob.getChecksums());
    }
  }
}
