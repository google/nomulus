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
import static google.registry.bsa.DownloadStage.CHECKSUMS_NOT_MATCH;
import static google.registry.bsa.DownloadStage.MAKE_DIFF;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockList;
import google.registry.bsa.DownloadStage;
import java.util.Optional;
import org.joda.time.DateTime;

/** Information needed when handling a download from BSA. */
@AutoValue
public abstract class DownloadSchedule {

  abstract long jobId();

  abstract DateTime jobCreationTime();

  public abstract String jobName();

  public abstract DownloadStage stage();

  /** The most recent job that ended in the {@code DONE} stage. */
  public abstract Optional<CompletedJob> latestCompleted();

  /**
   * Returns true if download should be processed even if the checksums show that it has not changed
   * from the previous one.
   */
  public abstract boolean alwaysDownload();

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
   * be one of {@code MAKE_DIFF}, {@code NOP}, or {@code CHECKSUMS_NOT_MATCH}.
   */
  public void updateJobStage(DownloadStage stage, ImmutableMap<BlockList, String> checksums) {
    checkArgument(
        stage.equals(MAKE_DIFF) || stage.equals(NOP) || stage.equals(CHECKSUMS_NOT_MATCH),
        "Invalid stage [%s]",
        stage);
    tm().transact(
            () -> {
              BsaDownload bsaDownload = tm().loadByKey(BsaDownload.vKey(jobId()));
              verify(
                  bsaDownload.getStage().equals(DownloadStage.DOWNLOAD),
                  "Invalid invocation. May only invoke during the DOWNLOAD stage.",
                  bsaDownload.getStage(),
                  stage);
              bsaDownload.setStage(stage);
              bsaDownload.setChecksums(checksums);
              tm().put(bsaDownload);
            });
  }

  static DownloadSchedule of(BsaDownload currentJob) {
    return new AutoValue_DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getCreationTime(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.empty(),
        /* alwaysDownload= */ true);
  }

  static DownloadSchedule of(
      BsaDownload currentJob, CompletedJob latestCompleted, boolean alwaysDownload) {
    return new AutoValue_DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getCreationTime(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.of(latestCompleted),
        /* alwaysDownload= */ alwaysDownload);
  }

  /** Information about a completed BSA download job. */
  @AutoValue
  public abstract static class CompletedJob {
    public abstract String jobName();

    public abstract ImmutableMap<BlockList, String> checksums();

    static CompletedJob of(BsaDownload completedJob) {
      return new AutoValue_DownloadSchedule_CompletedJob(
          completedJob.getJobName(), completedJob.getChecksums());
    }
  }
}
