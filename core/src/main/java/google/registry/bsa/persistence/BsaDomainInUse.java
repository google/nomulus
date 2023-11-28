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

import com.google.common.base.Objects;
import google.registry.bsa.persistence.BsaDomainInUse.BsaDomainInUseId;
import google.registry.model.CreateAutoTimestamp;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;

/** A domain matching a BSA label but is in use (registered or reserved), so cannot be blocked. */
@Entity
@IdClass(BsaDomainInUseId.class)
public class BsaDomainInUse {
  @Id String label;
  @Id String tld;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Reason reason;

  /**
   * Creation time of this record, which is the most recent time when the domain was detected to be
   * in use wrt BSA. It may be during the processing of a download, or during some other job that
   * refreshes the state.
   *
   * <p>This field is for information only.
   */
  @SuppressWarnings("unused")
  @Column(nullable = false)
  CreateAutoTimestamp createTime = CreateAutoTimestamp.create(null);

  // For Hibernate
  BsaDomainInUse() {}

  public BsaDomainInUse(String label, String tld, Reason reason) {
    this.label = label;
    this.tld = tld;
    this.reason = reason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaDomainInUse)) {
      return false;
    }
    BsaDomainInUse that = (BsaDomainInUse) o;
    return Objects.equal(label, that.label)
        && Objects.equal(tld, that.tld)
        && reason == that.reason
        && Objects.equal(createTime, that.createTime);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label, tld, reason, createTime);
  }

  enum Reason {
    REGISTERED,
    RESERVED;
  }

  static class BsaDomainInUseId implements Serializable {

    private String label;
    private String tld;

    // For Hibernate
    @SuppressWarnings("unused")
    BsaDomainInUseId() {}

    BsaDomainInUseId(String label, String tld) {
      this.label = label;
      this.tld = tld;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof BsaDomainInUseId) {
        BsaDomainInUseId otherId = (BsaDomainInUseId) other;
        return Objects.equal(otherId.label, label) && Objects.equal(otherId.tld, tld);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(label, tld);
    }
  }

  static VKey<BsaDomainInUse> vKey(String label, String tld) {
    return VKey.create(BsaDomainInUse.class, new BsaDomainInUseId(label, tld));
  }
}
