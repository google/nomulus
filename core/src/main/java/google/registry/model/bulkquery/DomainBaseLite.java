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

import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;

/**
 * A 'light' version of {@link DomainBase} with only base table ("Domain") attributes. Please refer
 * to {@link BulkQueryEntities} for usage.
 */
@Entity(name = "Domain")
@WithStringVKey
@Access(AccessType.FIELD)
public class DomainBaseLite extends DomainContent implements SqlOnlyEntity {

  @Override
  @javax.persistence.Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  public static VKey<DomainBaseLite> createVKey(String repoId) {
    return VKey.createSql(DomainBaseLite.class, repoId);
  }
}
