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

import static google.registry.bsa.DownloadStage.CHECKSUMS_NOT_MATCH;
import static google.registry.bsa.DownloadStage.DONE;
import static google.registry.bsa.DownloadStage.NOP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Duration;

public final class DownloadScheduler {

  private final Duration downloadInterval;
  private final Clock clock;

  @Inject
  DownloadScheduler(Duration downloadInterval, Clock clock) {
    this.downloadInterval = downloadInterval;
    this.clock = clock;
  }

  public Optional<DownloadSchedule> schedule() {
    return tm().transact(
            () -> {
              ImmutableList<BsaDownload> recentJobs = loadRecentProcessedJobs();
              if (recentJobs.isEmpty()) {
                return scheduleNewJob();
              }
              BsaDownload mostRecent = recentJobs.get(0);
              if (mostRecent.getStage().equals(DONE)) {
                return mostRecent.getCreationTime().plus(downloadInterval).isBefore(clock.nowUtc())
                    ? scheduleNewJob()
                    : Optional.<DownloadSchedule>empty();
              } else if (recentJobs.size() == 1) {
                return scheduleNewJob();
              } else {
                BsaDownload prev = recentJobs.get(1);
                Verify.verify(prev.getStage().equals(DONE), "Unexpectedly found two ongoing jobs.");
                return Optional.of(
                    DownloadSchedule.of(mostRecent, Optional.of(CompletedJob.of(prev))));
              }
            });
  }

  Optional<DownloadSchedule> scheduleNewJob() {
    BsaDownload job = new BsaDownload();
    tm().insert(job);
    return Optional.of(DownloadSchedule.of(job, Optional.empty()));
  }

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
