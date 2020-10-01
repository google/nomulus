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
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import java.util.Set;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.OneToMany;
import javax.persistence.PostLoad;
import javax.persistence.Table;
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
@com.googlecode.objectify.annotation.Entity
@Entity(name = "Domain")
@Table(
    name = "Domain",
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "currentSponsorRegistrarId"),
      @Index(columnList = "deletionTime"),
      @Index(columnList = "domainName"),
      @Index(columnList = "tld"),
      @Index(columnList = "autorenewEndTime")
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

  @ElementCollection
  @JoinTable(name = "DomainHost")
  @Access(AccessType.PROPERTY)
  @Column(name = "host_repo_id")
  public Set<VKey<HostResource>> getNsHosts() {
    return super.nsHosts;
  }

  /**
   * Returns the set of {@link GracePeriod} associated with the domain.
   *
   * <p>This is the getter method specific for Hibernate to access the field so it is set to
   * private. The caller can use the public {@link #getGracePeriods()} to get the grace periods.
   *
   * <p>Note that we need to set `insertable = false, updatable = false` for @JoinColumn, otherwise
   * Hibernate would try to set the foreign key to null(through an UPDATE TABLE sql) instead of
   * deleting the whole entry from the table when the {@link GracePeriod} is removed from the set.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  @JoinColumn(
      name = "domainRepoId",
      referencedColumnName = "repoId",
      insertable = false,
      updatable = false)
  @SuppressWarnings("UnusedMethod")
  private Set<GracePeriod> getInternalGracePeriods() {
    return gracePeriods;
  }

  @PostLoad
  @SuppressWarnings("UnusedMethod")
  private final void postLoad() {
    restoreOfyKeys(getRepoId());
  }

  @Override
  public VKey<DomainBase> createVKey() {
    return VKey.create(DomainBase.class, getRepoId(), Key.create(this));
  }

  @Override
  public DomainBase cloneProjectedAtTime(final DateTime now) {
    return cloneDomainProjectedAtTime(this, now);
  }

  public static VKey<DomainBase> createVKey(Key<DomainBase> key) {
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
