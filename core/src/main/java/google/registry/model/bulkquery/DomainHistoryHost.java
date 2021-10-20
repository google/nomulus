// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.host.HostResource;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;

/**
 * A name server host referenced by a {@link google.registry.model.domain.DomainHistory} record.
 * Please refer to {@link BulkQueryEntities} for usage.
 */
@Entity
@Access(AccessType.FIELD)
@IdClass(DomainHistoryHost.class)
public class DomainHistoryHost implements Serializable, SqlOnlyEntity {

  @Id private Long domainHistoryHistoryRevisionId;
  @Id private String domainHistoryDomainRepoId;
  @Id private String hostRepoId;

  private DomainHistoryHost() {}

  public DomainHistoryId getDomainHistoryId() {
    return new DomainHistoryId(domainHistoryDomainRepoId, domainHistoryHistoryRevisionId);
  }

  public VKey<HostResource> getHostVKey() {
    return VKey.create(HostResource.class, hostRepoId);
  }
}
