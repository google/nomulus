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

package google.registry.model.host;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.domain.DomainBase;
import google.registry.model.transfer.TransferData;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.VKeyConverter;
import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.ElementCollection;
import org.joda.time.DateTime;

/**
 * A persistable Host resource including mutable and non-mutable fields.
 *
 * <p>A host's {@link TransferData} is stored on the superordinate domain. Non-subordinate hosts
 * don't carry a full set of TransferData; all they have is lastTransferTime.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5732">RFC 5732</a>
 */
@ReportedOn
@Entity
@javax.persistence.Entity
@ExternalMessagingName("host")
public class HostResource extends EppResource implements ForeignKeyedEppResource {

  /**
   * Fully qualified hostname, which is a unique identifier for this host.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one host in Datastore with this name.
   * However, there can be many hosts with the same name and non-overlapping lifetimes.
   */
  @Index
  String fullyQualifiedHostName;

  /** IP Addresses for this host. Can be null if this is an external host. */
  @Index @ElementCollection Set<InetAddress> inetAddresses;

  /** The superordinate domain of this host, or null if this is an external host. */
  @Index
  @IgnoreSave(IfNull.class)
  @DoNotHydrate
  Key<DomainBase> superordinateDomain;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  DateTime lastTransferTime;

  /**
   * The most recent time that the {@link #superordinateDomain} field was changed.
   *
   * <p>This should be updated whenever the superordinate domain changes, including when it is set
   * to null. This field will be null for new hosts that have never experienced a change of
   * superordinate domain.
   */
  DateTime lastSuperordinateChange;

  public String getFullyQualifiedHostName() {
    return fullyQualifiedHostName;
  }

  public Key<DomainBase> getSuperordinateDomain() {
    return superordinateDomain;
  }

  public boolean isSubordinate() {
    return superordinateDomain != null;
  }

  public ImmutableSet<InetAddress> getInetAddresses() {
    return nullToEmptyImmutableCopy(inetAddresses);
  }

  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  public DateTime getLastSuperordinateChange() {
    return lastSuperordinateChange;
  }

  @Override
  public String getForeignKey() {
    return fullyQualifiedHostName;
  }

  public VKey<HostResource> createKey() {
    return VKey.createOfy(HostResource.class, Key.create(this));
  }

  @Deprecated
  @Override
  public HostResource cloneProjectedAtTime(DateTime now) {
    return this;
  }

  /**
   * Compute the correct last transfer time for this host given its loaded superordinate domain.
   *
   * <p>Hosts can move between superordinate domains, so to know which lastTransferTime is correct
   * we need to know if the host was attached to this superordinate the last time that the
   * superordinate was transferred. If the last superordinate change was before this time, then the
   * host was attached to this superordinate domain during that transfer.
   *
   * <p>If the host is not subordinate the domain can be null and we just return last transfer time.
   *
   * @param superordinateDomain the loaded superordinate domain, which must match the key in
   *     the {@link #superordinateDomain} field. Passing it as a parameter allows the caller to
   *     control the degree of consistency used to load it.
   */
   public DateTime computeLastTransferTime(@Nullable DomainBase superordinateDomain) {
    if (!isSubordinate()) {
      checkArgument(superordinateDomain == null);
      return getLastTransferTime();
    }
    checkArgument(
        superordinateDomain != null
            && Key.create(superordinateDomain).equals(getSuperordinateDomain()));
    DateTime lastSuperordinateChange =
        Optional.ofNullable(getLastSuperordinateChange()).orElse(getCreationTime());
    DateTime lastTransferOfCurrentSuperordinate =
        Optional.ofNullable(superordinateDomain.getLastTransferTime()).orElse(START_OF_TIME);
    return lastSuperordinateChange.isBefore(lastTransferOfCurrentSuperordinate)
        ? superordinateDomain.getLastTransferTime()
        : getLastTransferTime();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link HostResource}, since it is immutable. */
  public static class Builder extends EppResource.Builder<HostResource, Builder> {
    public Builder() {}

    private Builder(HostResource instance) {
      super(instance);
    }

    public Builder setFullyQualifiedHostName(String fullyQualifiedHostName) {
      checkArgument(
          fullyQualifiedHostName.equals(canonicalizeDomainName(fullyQualifiedHostName)),
          "Host name must be in puny-coded, lower-case form");
      getInstance().fullyQualifiedHostName = fullyQualifiedHostName;
      return this;
    }

    public Builder setInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
      getInstance().inetAddresses = inetAddresses;
      return this;
    }

    public Builder setLastSuperordinateChange(DateTime lastSuperordinateChange) {
      getInstance().lastSuperordinateChange = lastSuperordinateChange;
      return this;
    }

    public Builder addInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
      return setInetAddresses(ImmutableSet.copyOf(
          union(getInstance().getInetAddresses(), inetAddresses)));
    }

    public Builder removeInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
      return setInetAddresses(ImmutableSet.copyOf(
          difference(getInstance().getInetAddresses(), inetAddresses)));
    }

    public Builder setSuperordinateDomain(Key<DomainBase> superordinateDomain) {
      getInstance().superordinateDomain = superordinateDomain;
      return this;
    }

    public Builder setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return this;
    }
  }

  public static class VKeyHostResourceConverter extends VKeyConverter<HostResource> {
    @Override
    protected Class<HostResource> getAttributeClass() {
      return HostResource.class;
    }
  }
}
