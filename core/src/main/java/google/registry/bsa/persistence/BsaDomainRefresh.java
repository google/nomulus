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

import static google.registry.bsa.persistence.BsaDomainRefresh.Stage.MAKE_DIFF;

import com.google.common.base.Objects;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.persistence.VKey;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import org.joda.time.DateTime;

/**
 * Records of completed and ongoing refresh actions, which recomputes the set of unblockable domains
 * and reports changes to BSA.
 *
 * <p>The refresh action only handles registered and reserved domain names. Invalid names only
 * change status when the IDN tables change, and will be handled by a separate tool when it happens.
 */
@Entity
public class BsaDomainRefresh {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long jobId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create(null);

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Stage stage = MAKE_DIFF;

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
  public String getJobName() {
    return "refresh-" + getCreationTime().toString();
  }

  public Stage getStage() {
    return this.stage;
  }

  BsaDomainRefresh setStage(Stage stage) {
    this.stage = stage;
    return this;
  }

  VKey<BsaDomainRefresh> vKey() {
    return vKey(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaDomainRefresh)) {
      return false;
    }
    BsaDomainRefresh that = (BsaDomainRefresh) o;
    return Objects.equal(jobId, that.jobId)
        && Objects.equal(creationTime, that.creationTime)
        && Objects.equal(updateTime, that.updateTime)
        && stage == that.stage;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(jobId, creationTime, updateTime, stage);
  }

  static VKey vKey(BsaDomainRefresh bsaDomainRefresh) {
    return VKey.create(BsaDomainRefresh.class, bsaDomainRefresh.jobId);
  }

  enum Stage {
    MAKE_DIFF,
    APPLY_DIFF,
    REPORT_REMOVALS,
    REPORT_ADDITIONS;
  }
}
