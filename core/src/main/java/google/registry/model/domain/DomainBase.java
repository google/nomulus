// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import javax.persistence.Access;
import javax.persistence.AccessType;
import org.joda.time.DateTime;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * <p>For historical reasons, the name of this entity is "DomainBase". Ideally it would be
 * "DomainResource" for linguistic parallelism with the other {@link EppResource} entity classes,
 * but that would necessitate a complex data migration which isn't worth it.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5731">RFC 5731</a>
 */
@ReportedOn
@Entity
@javax.persistence.Entity(name = "Domain")
@javax.persistence.Table(
    name = "Domain",
    indexes = {
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "currentSponsorRegistrarId"),
      @javax.persistence.Index(columnList = "deletionTime"),
      @javax.persistence.Index(columnList = "domainName"),
      @javax.persistence.Index(columnList = "tld")
    })
@WithStringVKey
@ExternalMessagingName("domain")
@Access(AccessType.FIELD)
public class DomainBase extends DomainContent
    implements DatastoreAndSqlEntity, ForeignKeyedEppResource {

  @Override
  @javax.persistence.Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  @Override
  public VKey<DomainBase> createVKey() {
    return VKey.create(DomainBase.class, getRepoId(), Key.create(this));
  }

  @Override
  public DomainBase cloneProjectedAtTime(final DateTime now) {
    return cloneDomainProjectedAtTime(this, now);
  }

  public static VKey<DomainBase> createVKey(Key key) {
    return VKey.create(DomainBase.class, key.getName(), key);
  }

  /** An override of {@link EppResource#asBuilder} with tighter typing. */
  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link DomainBase}, since it is immutable. */
  public static class Builder extends DomainContent.Builder<DomainBase, Builder> {

    public Builder() {}

    Builder(DomainBase instance) {
      super(instance);
    }
  }
}
