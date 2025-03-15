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

import static google.registry.bsa.persistence.DownloadScheduler.fetchMostRecentDownload;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.DateTime;

/** Assigns work for each cron invocation of domain refresh job. */
public class RefreshScheduler {

  @Inject
  RefreshScheduler() {}

  public Optional<RefreshSchedule> schedule() {
    return tm().transact(
            () -> {
              ImmutableList<BsaDomainRefresh> recentJobs = fetchMostRecentRefreshes();
              Optional<BsaDownload> mostRecentDownload = fetchMostRecentDownload();
              if (mostRecentDownload.isPresent() && !mostRecentDownload.get().isDone()) {
                // Ongoing download exists. Must wait it out.
                return Optional.empty();
              }
              if (recentJobs.size() > 1) {
                BsaDomainRefresh mostRecent = recentJobs.get(0);
                if (mostRecent.isDone()) {
                  return Optional.of(scheduleNewJob(mostRecent.getCreationTime()));
                } else {
                  return Optional.of(
                      rescheduleOngoingJob(mostRecent, recentJobs.get(1).getCreationTime()));
                }
              }
              if (recentJobs.size() == 1 && recentJobs.get(0).isDone()) {
                return Optional.of(scheduleNewJob(recentJobs.get(0).getCreationTime()));
              }
              // No previously completed refreshes. Need start time of a completed download as
              // lower bound of refresh checks.
              if (mostRecentDownload.isEmpty()) {
                return Optional.empty();
              }

              DateTime prevDownloadTime = mostRecentDownload.get().getCreationTime();
              if (recentJobs.isEmpty()) {
                return Optional.of(scheduleNewJob(prevDownloadTime));
              } else {
                return Optional.of(rescheduleOngoingJob(recentJobs.get(0), prevDownloadTime));
              }
            });
  }

  RefreshSchedule scheduleNewJob(DateTime prevRefreshTime) {
    BsaDomainRefresh newJob = new BsaDomainRefresh();
    tm().insert(newJob);
    return RefreshSchedule.create(newJob, prevRefreshTime);
  }

  RefreshSchedule rescheduleOngoingJob(BsaDomainRefresh ongoingJob, DateTime prevJobStartTime) {
    return RefreshSchedule.create(ongoingJob, prevJobStartTime);
  }

  @VisibleForTesting
  static ImmutableList<BsaDomainRefresh> fetchMostRecentRefreshes() {
    return ImmutableList.copyOf(
        tm().getEntityManager()
            .createQuery("FROM BsaDomainRefresh ORDER BY creationTime DESC", BsaDomainRefresh.class)
            .setMaxResults(2)
            .getResultList());
  }

  static Optional<BsaDomainRefresh> fetchMostRecentRefresh() {
    return fetchMostRecentRefreshes().stream().findFirst();
  }
}
