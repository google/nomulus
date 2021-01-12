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

package google.registry.beam.initsql;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.appengine.api.datastore.Entity;
import java.util.Objects;

/** Helper for manipulating {@code DomainBase} when migrating from Datastore to SQL database */
final class DomainBaseUtil {

  private DomainBaseUtil() {}

  /**
   * Removes properties that contain foreign keys from a Datastore {@link Entity} that represents an
   * Ofy {@link google.registry.model.domain.DomainBase}. This breaks the cycle of foreign key
   * constraints between entity kinds, allowing {@code DomainBases} to be inserted into the SQL
   * database. See {@link InitSqlPipeline} for a use case, where the full {@code DomainBases} are
   * written again during the last stage of the pipeline.
   *
   * <p>The returned object may be in bad state. Specifically, {@link
   * google.registry.model.eppcommon.StatusValue#INACTIVE} is not added after name servers are
   * removed. This only impacts tests that manipulate Datastore entities directly.
   *
   * <p>This operation is performed on an Datastore {@link Entity} instead of Ofy Java object
   * because Objectify requires access to a Datastore service when converting an Ofy object to a
   * Datastore {@code Entity}. If we insist on working with Objectify objects, we face a few
   * unsatisfactory options:
   *
   * <ul>
   *   <li>Connect to our production Datastore, which incurs unnecessary security and code health
   *       risk.
   *   <li>Connect to a separate real Datastore instance, which is a waster and overkill.
   *   <li>Use an in-memory test Datastore, which is a project health risk in that the test
   *       Datastore would be added to Nomulus' production binary unless we create a separate
   *       project for this pipeline.
   * </ul>
   *
   * <p>Given our use case, operating on Datastore entities is the best option.
   *
   * @throws IllegalArgumentException if input does not represent a DomainBase
   */
  static Entity removeBillingAndPollAndHosts(Entity domainBase) {
    checkNotNull(domainBase, "domainBase");
    checkArgument(
        Objects.equals(domainBase.getKind(), "DomainBase"),
        "Expecting DomainBase, got %s",
        domainBase.getKind());
    Entity clone = domainBase.clone();
    clone.removeProperty("autorenewBillingEvent");
    clone.removeProperty("autorenewPollMessage");
    clone.removeProperty("deletePollMessage");
    clone.removeProperty("nsHosts");
    domainBase.getProperties().keySet().stream()
        .filter(s -> s.startsWith("transferData."))
        .forEach(s -> clone.removeProperty(s));
    domainBase.getProperties().keySet().stream()
        .filter(s -> s.startsWith("gracePeriods."))
        .forEach(s -> clone.removeProperty(s));
    return clone;
  }
}
