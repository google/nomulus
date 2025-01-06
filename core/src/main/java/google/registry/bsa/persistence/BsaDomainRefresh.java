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

import static google.registry.bsa.RefreshStage.CHECK_FOR_CHANGES;
import static google.registry.bsa.RefreshStage.DONE;

import com.google.common.base.Objects;
import google.registry.bsa.RefreshStage;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.persistence.VKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.joda.time.DateTime;

/**
 * Records of completed and ongoing refresh actions, which recomputes the set of unblockable domains
 * and reports changes to BSA.
 *
 * <p>The refresh action only handles registered and reserved domain names. Invalid names only
 * change status when the IDN tables change, and will be handled by a separate tool when it happens.
 */
@Entity
class BsaDomainRefresh {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long jobId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create(null);

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  RefreshStage stage = CHECK_FOR_CHANGES;

  BsaDomainRefresh() {}

  long getJobId() {
    return jobId;
  }

  DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  /**
   * Returns the starting time of this job as a string, which can be used as folder name on GCS when
   * storing download data.
   */
  String getJobName() {
    return getCreationTime().toString() + "-refresh";
  }

  boolean isDone() {
    return java.util.Objects.equals(stage, DONE);
  }

  RefreshStage getStage() {
    return this.stage;
  }

  BsaDomainRefresh setStage(RefreshStage refreshStage) {
    this.stage = refreshStage;
    return this;
  }

  @SuppressWarnings("AmbiguousMethodReference")
  VKey<BsaDomainRefresh> vKey() {
    return vKey(jobId);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaDomainRefresh that)) {
      return false;
    }
    return Objects.equal(jobId, that.jobId)
        && Objects.equal(creationTime, that.creationTime)
        && Objects.equal(updateTime, that.updateTime)
        && stage == that.stage;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(jobId, creationTime, updateTime, stage);
  }

  static VKey<BsaDomainRefresh> vKey(long jobId) {
    return VKey.create(BsaDomainRefresh.class, jobId);
  }
}
