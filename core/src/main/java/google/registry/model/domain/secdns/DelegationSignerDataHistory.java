// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain.secdns;

import google.registry.model.domain.DomainHistory;
import google.registry.model.ofy.ObjectifyService;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

/** Entity class to represent a historic {@link DelegationSignerData}. */
@Entity
public class DelegationSignerDataHistory extends DelegationSignerDataBase {

  @Id Long dsDataHistoryRevisionId;

  /** ID of the {@link DomainHistory} entity that this entity is associated with. */
  @Column(nullable = false)
  Long domainHistoryRevisionId;

  private DelegationSignerDataHistory() {}

  /**
   * Creates a {@link DelegationSignerDataHistory} instance from given {@link
   * #domainHistoryRevisionId} and {@link DelegationSignerData} instance.
   */
  public static DelegationSignerDataHistory createFrom(
      long domainHistoryRevisionId, DelegationSignerData dsData) {
    DelegationSignerDataHistory instance = new DelegationSignerDataHistory();
    instance.domainHistoryRevisionId = domainHistoryRevisionId;
    instance.domainRepoId = dsData.domainRepoId;
    instance.keyTag = dsData.getKeyTag();
    instance.algorithm = dsData.getAlgorithm();
    instance.digestType = dsData.getDigestType();
    instance.digest = dsData.getDigest();
    instance.dsDataHistoryRevisionId = ObjectifyService.allocateId();
    return instance;
  }

  @Override
  @Access(AccessType.PROPERTY)
  public int getKeyTag() {
    return super.getKeyTag();
  }

  @Override
  @Access(AccessType.PROPERTY)
  public int getAlgorithm() {
    return super.getAlgorithm();
  }

  @Override
  @Access(AccessType.PROPERTY)
  public int getDigestType() {
    return super.getDigestType();
  }

  @Override
  @Access(AccessType.PROPERTY)
  @Column(nullable = false)
  public byte[] getDigest() {
    return super.getDigest();
  }
}
