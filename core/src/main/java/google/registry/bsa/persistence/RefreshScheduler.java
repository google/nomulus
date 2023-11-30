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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.Duration.standardSeconds;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.persistence.BsaDomainRefresh.Stage;
import google.registry.util.Clock;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Duration;

/** Assigns work for each cron invocation of domain refresh job. */
public class RefreshScheduler {

  /** Allows a new download to proceed if the cron job fires a little early due to NTP drift. */
  private static final Duration CRON_JITTER = standardSeconds(5);

  private final Clock clock;

  @Inject
  RefreshScheduler(Clock clock) {
    this.clock = clock;
  }

  public RefreshSchedule schedule() {
    return tm().transact(
            () -> {
              ImmutableList<BsaDomainRefresh> prevJobs = loadRecentJobs();
              if (prevJobs.isEmpty()) {
                return scheduleNewJob(Optional.empty());
              }
              if (Objects.equals(prevJobs.get(0).getStage(), Stage.DONE)) {
                return scheduleNewJob(Optional.of(prevJobs.get(0)));
              } else if (prevJobs.size() == 1) {
                return RefreshSchedule.of(prevJobs.get(0), Optional.empty());
              } else {
                return RefreshSchedule.of(
                    prevJobs.get(0), Optional.of(prevJobs.get(1).getCreationTime()));
              }
            });
  }

  RefreshSchedule scheduleNewJob(Optional<BsaDomainRefresh> prevJob) {
    BsaDomainRefresh newJob = new BsaDomainRefresh();
    tm().insert(newJob);
    return RefreshSchedule.of(newJob, prevJob.map(BsaDomainRefresh::getCreationTime));
  }

  @VisibleForTesting
  ImmutableList<BsaDomainRefresh> loadRecentJobs() {
    return ImmutableList.copyOf(
        tm().getEntityManager()
            .createQuery("FROM BsaDomainRefresh ORDER BY creationTime DESC", BsaDomainRefresh.class)
            .setMaxResults(2)
            .getResultList());
  }
}
