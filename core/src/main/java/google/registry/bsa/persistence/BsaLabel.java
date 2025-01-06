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

import com.google.common.base.Objects;
import google.registry.persistence.VKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.joda.time.DateTime;

/**
 * Specifies a second-level TLD name that should be blocked from registration in all TLDs except by
 * the label's owner.
 *
 * <p>The label is valid (wrt IDN) in at least one TLD.
 */
@Entity
final class BsaLabel {

  @Id String label;

  /**
   * Creation time of this label. This field is for human use, and should give the name of the GCS
   * folder that contains the downloaded BSA data.
   *
   * <p>See {@link BsaDownload#getCreationTime} and {@link BsaDownload#getJobName} for more
   * information.
   */
  @SuppressWarnings("unused")
  @Column(nullable = false)
  DateTime creationTime;

  // For Hibernate.
  private BsaLabel() {}

  BsaLabel(String label, DateTime creationTime) {
    this.label = label;
    this.creationTime = creationTime;
  }

  /** Returns the label to be blocked. */
  String getLabel() {
    return label;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaLabel label1)) {
      return false;
    }
    return Objects.equal(label, label1.label) && Objects.equal(creationTime, label1.creationTime);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label, creationTime);
  }

  static VKey<BsaLabel> vKey(String label) {
    return VKey.create(BsaLabel.class, label);
  }
}
