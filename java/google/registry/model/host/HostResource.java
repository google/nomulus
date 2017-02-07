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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import java.net.InetAddress;
import java.util.Set;
import org.joda.time.DateTime;

/**
 * A persistable Host resource including mutable and non-mutable fields.
 *
 * <p>A host's {@link TransferData} is stored on the superordinate domain.  Non-subordinate hosts
 * don't carry a full set of TransferData; all they have is lastTransferTime.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5732">RFC 5732</a>
 */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@ReportedOn
@Entity
@ExternalMessagingName("host")
public class HostResource extends EppResource implements ForeignKeyedEppResource {

  /**
   * Fully qualified hostname, which is a unique identifier for this host.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one host in the datastore with this name.
   * However, there can be many hosts with the same name and non-overlapping lifetimes.
   */
  @Index
  String fullyQualifiedHostName;

  /** IP Addresses for this host. Can be null if this is an external host. */
  @Index
  Set<InetAddress> inetAddresses;

  /** The superordinate domain of this host, or null if this is an external host. */
  @Index
  @IgnoreSave(IfNull.class)
  @DoNotHydrate
  Key<DomainResource> superordinateDomain;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  DateTime lastTransferTime;

  /**
   * The most recent time that the superordinate domain was changed, or null if this host is
   * external.
   */
  DateTime lastSuperordinateChange;

  public String getFullyQualifiedHostName() {
    return fullyQualifiedHostName;
  }

  public Key<DomainResource> getSuperordinateDomain() {
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

  @Override
  public HostResource cloneProjectedAtTime(DateTime now) {
    Builder builder = this.asBuilder();

    if (superordinateDomain == null) {
      // If this was a subordinate host to a domain that was being transferred, there might be a
      // pending transfer still extant, so remove it.
      builder.removeStatusValue(StatusValue.PENDING_TRANSFER);
    } else {
      // For hosts with superordinate domains, the client id, last transfer time, and transfer
      // status value need to be read off the domain projected to the correct time.
      DomainResource domainAtTime = ofy().load().key(superordinateDomain).now()
          .cloneProjectedAtTime(now);
      builder.setCurrentSponsorClientId(domainAtTime.getCurrentSponsorClientId());
      // If the superordinate domain's last transfer time is what is relevant, because the host's
      // superordinate domain was last changed less recently than the domain's last transfer, then
      // use the last transfer time on the domain.
      if (Optional.fromNullable(lastSuperordinateChange).or(START_OF_TIME)
          .isBefore(Optional.fromNullable(domainAtTime.getLastTransferTime()).or(START_OF_TIME))) {
        builder.setLastTransferTime(domainAtTime.getLastTransferTime());
      }
      // Copy the transfer status from the superordinate domain onto the host, because the host's
      // doesn't matter and the superordinate domain always has the canonical data.
      TransferStatus domainTransferStatus = domainAtTime.getTransferData().getTransferStatus();
      if (TransferStatus.PENDING.equals(domainTransferStatus)) {
        builder.addStatusValue(StatusValue.PENDING_TRANSFER);
      } else {
        builder.removeStatusValue(StatusValue.PENDING_TRANSFER);
      }
    }
    return builder.build();
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

    public Builder setSuperordinateDomain(Key<DomainResource> superordinateDomain) {
      getInstance().superordinateDomain = superordinateDomain;
      return this;
    }

    public Builder setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return this;
    }
  }
}
