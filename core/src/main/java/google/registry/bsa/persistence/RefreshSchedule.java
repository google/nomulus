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
import static google.registry.bsa.RefreshStage.APPLY_CHANGES;
import static google.registry.bsa.RefreshStage.CHECK_FOR_CHANGES;
import static google.registry.bsa.RefreshStage.DONE;
import static google.registry.bsa.RefreshStage.UPLOAD_ADDITIONS;
import static google.registry.bsa.RefreshStage.UPLOAD_REMOVALS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import google.registry.bsa.RefreshStage;
import java.time.Instant;

/**
 * Information needed when handling a domain refresh.
 *
 * @param prevRefreshTime The most recent job that ended in the {@code DONE} stage.
 */
public record RefreshSchedule(
    long jobId,
    Instant jobCreationTime,
    String jobName,
    RefreshStage stage,
    Instant prevRefreshTime) {

  static final ImmutableMultimap<RefreshStage, RefreshStage> ALLOWED_TRANSITIONS =
      ImmutableMultimap.<RefreshStage, RefreshStage>builder()
          .put(CHECK_FOR_CHANGES, DONE)
          .put(CHECK_FOR_CHANGES, APPLY_CHANGES)
          .put(APPLY_CHANGES, UPLOAD_REMOVALS)
          .put(UPLOAD_REMOVALS, UPLOAD_ADDITIONS)
          .put(UPLOAD_ADDITIONS, DONE)
          .build();

  /** Updates the current job to the new stage. */
  @CanIgnoreReturnValue
  public RefreshSchedule updateJobStage(RefreshStage stage) {
    return tm().transact(
            () -> {
              BsaDomainRefresh bsaRefresh = tm().loadByKey(BsaDomainRefresh.vKey(jobId()));
              RefreshStage currentStage = bsaRefresh.getStage();
              verify(
                  ALLOWED_TRANSITIONS.containsEntry(currentStage, stage),
                  "Invalid new stage [%s]. Cannot transition from [%s]",
                  stage,
                  currentStage);
              bsaRefresh.setStage(stage);
              tm().put(bsaRefresh);
              return create(bsaRefresh, prevRefreshTime());
            });
  }

  static RefreshSchedule create(BsaDomainRefresh job, Instant prevJobCreationTime) {
    return new RefreshSchedule(
        job.getJobId(),
        job.getCreationTime(),
        job.getJobName(),
        job.getStage(),
        prevJobCreationTime);
  }
}
