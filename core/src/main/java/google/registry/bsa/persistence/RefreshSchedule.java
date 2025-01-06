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

import static com.google.common.base.Verify.verify;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import google.registry.bsa.RefreshStage;
import org.joda.time.DateTime;

/**
 * Information needed when handling a domain refresh.
 *
 * @param prevRefreshTime The most recent job that ended in the {@code DONE} stage.
 */
public record RefreshSchedule(
    long jobId,
    DateTime jobCreationTime,
    String jobName,
    RefreshStage stage,
    DateTime prevRefreshTime) {

  /** Updates the current job to the new stage. */
  @CanIgnoreReturnValue
  public RefreshSchedule updateJobStage(RefreshStage stage) {
    return tm().transact(
            () -> {
              BsaDomainRefresh bsaRefresh = tm().loadByKey(BsaDomainRefresh.vKey(jobId()));
              verify(
                  stage.compareTo(bsaRefresh.getStage()) > 0,
                  "Invalid new stage [%s]. Must move forward from [%s]",
                  bsaRefresh.getStage(),
                  stage);
              bsaRefresh.setStage(stage);
              tm().put(bsaRefresh);
              return create(bsaRefresh, prevRefreshTime());
            });
  }

  static RefreshSchedule create(BsaDomainRefresh job, DateTime prevJobCreationTime) {
    return new RefreshSchedule(
        job.getJobId(),
        job.getCreationTime(),
        job.getJobName(),
        job.getStage(),
        prevJobCreationTime);
  }
}
