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

import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;

/**
 * A 'light' version of {@link Domain} with only base table ("Domain") attributes, which allows fast
 * bulk loading. They are used in in-memory assembly of {@code Domain} instances along with
 * bulk-loaded child entities ({@code GracePeriod} etc). The in-memory assembly achieves much higher
 * performance than loading {@code Domain} directly.
 *
 * <p>Please refer to {@link BulkQueryEntities} for more information.
 */
@Entity(name = "Domain")
@WithVKey(String.class)
@Access(AccessType.FIELD)
public class DomainLite extends DomainBase {

  @Override
  @javax.persistence.Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  public static VKey<DomainLite> createVKey(String repoId) {
    return VKey.createSql(DomainLite.class, repoId);
  }
}
