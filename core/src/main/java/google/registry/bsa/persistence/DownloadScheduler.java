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

import static com.google.common.base.Verify.verify;
import static google.registry.bsa.DownloadStage.CHECKSUMS_NOT_MATCH;
import static google.registry.bsa.DownloadStage.DONE;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.Duration.standardSeconds;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * Assigns work for each cron invocation of the BSA Download job.
 *
 * <p>The download job is invoked at a divisible fraction of the desired data freshness to
 * accommodate potential retries. E.g., for 30-minute data freshness with up to two retries on
 * error, the cron schedule for the job should be set to 10 minutes.
 *
 * <p>The processing of each BSA download progresses through multiple stages as described in {@code
 * DownloadStage} until it reaches one of the terminal stages. Each stage is check-pointed on
 * completion, therefore if an invocation fails mid-process, the next invocation will skip the
 * completed stages. No new downloads will start as long as the most recent one is still being
 * processed.
 *
 * <p>When a new download is scheduled, the block list checksums from the most recent completed job
 * is included. If the new checksums match the previous ones, the download may be skipped and the
 * job should terminate in the {@code NOP} stage. However, if the checksums have stayed unchanged
 * for longer than the user-provided {@code maxNopInterval}, the download will be processed.
 *
 * <p>The BSA downloads contains server-provided checksums. If they do not match the checksums
 * generated on Nomulus' side, the download is skipped and the job should terminate in the {@code
 * CHECKSUMS_NOT_MATCH} stage.
 */
public final class DownloadScheduler {

  /** Allows a new download to proceed if the cron job fires a little early due to NTP drift. */
  private static final Duration CRON_JITTER = standardSeconds(5);

  private final Duration downloadInterval;
  private final Duration maxNopInterval;
  private final Clock clock;

  @Inject
  DownloadScheduler(
      @Config("bsaDownloadInterval") Duration downloadInterval,
      @Config("bsaMaxNopInterval") Duration maxNopInterval,
      Clock clock) {
    this.downloadInterval = downloadInterval;
    this.maxNopInterval = maxNopInterval;
    this.clock = clock;
  }

  /**
   * Returns a {@link DownloadSchedule} instance that describes the work to be performed by an
   * invocation of the download action, if applicable; or {@link Optional#empty} when there is
   * nothing to do.
   */
  public Optional<DownloadSchedule> schedule() {
    return tm().transact(
            () -> {
              ImmutableList<BsaDownload> recentJobs = loadRecentProcessedJobs();
              if (recentJobs.isEmpty()) {
                // No jobs initiated ever.
                return Optional.of(scheduleNewJob(Optional.empty()));
              }
              BsaDownload mostRecent = recentJobs.get(0);
              if (mostRecent.getStage().equals(DONE)) {
                return isTimeAgain(mostRecent, downloadInterval)
                    ? Optional.of(scheduleNewJob(Optional.of(mostRecent)))
                    : Optional.empty();
              } else if (recentJobs.size() == 1) {
                // First job ever, still in progress
                return Optional.of(DownloadSchedule.of(recentJobs.get(0)));
              } else {
                // Job in progress, with completed previous jobs.
                BsaDownload prev = recentJobs.get(1);
                verify(prev.getStage().equals(DONE), "Unexpectedly found two ongoing jobs.");
                return Optional.of(
                    DownloadSchedule.of(
                        mostRecent,
                        CompletedJob.of(prev),
                        isTimeAgain(mostRecent, maxNopInterval)));
              }
            });
  }

  private boolean isTimeAgain(BsaDownload mostRecent, Duration interval) {
    return mostRecent.getCreationTime().plus(interval).minus(CRON_JITTER).isBefore(clock.nowUtc());
  }

  /**
   * Adds a new {@link BsaDownload} to the database and returns a {@link DownloadSchedule} for it.
   */
  private DownloadSchedule scheduleNewJob(Optional<BsaDownload> prevJob) {
    BsaDownload job = new BsaDownload();
    tm().insert(job);
    return prevJob
        .map(
            prev ->
                DownloadSchedule.of(job, CompletedJob.of(prev), isTimeAgain(prev, maxNopInterval)))
        .orElseGet(() -> DownloadSchedule.of(job));
  }

  @VisibleForTesting
  ImmutableList<BsaDownload> loadRecentProcessedJobs() {
    return ImmutableList.copyOf(
        tm().getEntityManager()
            .createQuery(
                "FROM BsaDownload WHERE stage NOT IN :nop_stages ORDER BY creationTime DESC")
            .setParameter("nop_stages", ImmutableList.of(CHECKSUMS_NOT_MATCH, NOP))
            .setMaxResults(2)
            .getResultList());
  }
}
