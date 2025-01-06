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
import static google.registry.bsa.BsaStringUtils.DOMAIN_JOINER;
import static google.registry.bsa.BsaStringUtils.DOMAIN_SPLITTER;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.persistence.BsaUnblockableDomain.BsaUnblockableDomainId;
import google.registry.model.CreateAutoTimestamp;
import google.registry.persistence.VKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;

/** A domain matching a BSA label but is in use (registered or reserved), so cannot be blocked. */
@Entity
@IdClass(BsaUnblockableDomainId.class)
class BsaUnblockableDomain {
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
  BsaUnblockableDomain() {}

  BsaUnblockableDomain(String label, String tld, Reason reason) {
    this.label = label;
    this.tld = tld;
    this.reason = reason;
  }

  String domainName() {
    return DOMAIN_JOINER.join(label, tld);
  }

  /**
   * Returns the equivalent {@link UnblockableDomain} instance, for use by communication with the
   * BSA API.
   */
  UnblockableDomain toUnblockableDomain() {
    return UnblockableDomain.of(label, tld, UnblockableDomain.Reason.valueOf(reason.name()));
  }

  VKey<BsaUnblockableDomain> toVkey() {
    return vKey(this.label, this.tld);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaUnblockableDomain that)) {
      return false;
    }
    return Objects.equal(label, that.label)
        && Objects.equal(tld, that.tld)
        && reason == that.reason
        && Objects.equal(createTime, that.createTime);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label, tld, reason, createTime);
  }

  static BsaUnblockableDomain of(String domainName, Reason reason) {
    ImmutableList<String> parts = ImmutableList.copyOf(DOMAIN_SPLITTER.splitToList(domainName));
    verify(parts.size() == 2, "Invalid domain name: %s", domainName);
    return new BsaUnblockableDomain(parts.get(0), parts.get(1), reason);
  }

  static BsaUnblockableDomain of(UnblockableDomain unblockable) {
    return of(unblockable.domainName(), Reason.valueOf(unblockable.reason().name()));
  }

  static VKey<BsaUnblockableDomain> vKey(String domainName) {
    ImmutableList<String> parts = ImmutableList.copyOf(DOMAIN_SPLITTER.splitToList(domainName));
    verify(parts.size() == 2, "Invalid domain name: %s", domainName);
    return vKey(parts.get(0), parts.get(1));
  }

  static VKey<BsaUnblockableDomain> vKey(String label, String tld) {
    return VKey.create(BsaUnblockableDomain.class, new BsaUnblockableDomainId(label, tld));
  }

  enum Reason {
    REGISTERED,
    RESERVED;
  }

  static class BsaUnblockableDomainId implements Serializable {

    private String label;
    private String tld;

    @SuppressWarnings("unused") // For Hibernate
    BsaUnblockableDomainId() {}

    BsaUnblockableDomainId(String label, String tld) {
      this.label = label;
      this.tld = tld;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof BsaUnblockableDomainId that)) {
        return false;
      }
      return Objects.equal(label, that.label) && Objects.equal(tld, that.tld);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(label, tld);
    }
  }
}
