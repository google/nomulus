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

package google.registry.model.domain;

import static google.registry.util.CollectionUtils.forceEmptyToNull;

import com.google.auto.value.AutoValue;
import com.google.auto.value.AutoValue.CopyAnnotations;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** The {@link ResponseData} returned for an EPP info flow on a domain. */
@XmlRootElement(name = "infData")
@XmlType(
    propOrder = {
      "domainName",
      "repoId",
      "statusValues",
      "registrant",
      "contacts",
      "nameservers",
      "subordinateHosts",
      "currentSponsorRegistrarId",
      "creationRegistrarId",
      "creationTime",
      "lastEppUpdateRegistrarId",
      "lastEppUpdateTime",
      "registrationExpirationTime",
      "lastTransferTime",
      "authInfo"
    })
@AutoValue
@CopyAnnotations
public abstract class DomainInfoData implements ResponseData {

  @XmlElement(name = "name")
  abstract String getDomainName();

  @XmlElement(name = "roid")
  abstract String getRepoId();

  @XmlElement(name = "status")
  @Nullable
  abstract ImmutableSet<StatusValue> getStatusValues();

  @XmlElement(name = "registrant")
  @Nullable
  abstract String getRegistrant();

  @XmlElement(name = "contact")
  @Nullable
  abstract ImmutableSet<ForeignKeyedDesignatedContact> getContacts();

  @XmlElementWrapper(name = "ns")
  @XmlElement(name = "hostObj")
  @Nullable
  abstract ImmutableSet<String> getNameservers();

  @XmlElement(name = "host")
  @Nullable
  abstract ImmutableSet<String> getSubordinateHosts();

  @XmlElement(name = "clID")
  abstract String getCurrentSponsorRegistrarId();

  @XmlElement(name = "crID")
  @Nullable
  abstract String getCreationRegistrarId();

  @XmlElement(name = "crDate")
  @Nullable
  abstract DateTime getCreationTime();

  @XmlElement(name = "upID")
  @Nullable
  abstract String getLastEppUpdateRegistrarId();

  @XmlElement(name = "upDate")
  @Nullable
  abstract DateTime getLastEppUpdateTime();

  @XmlElement(name = "exDate")
  @Nullable
  abstract DateTime getRegistrationExpirationTime();

  @XmlElement(name = "trDate")
  @Nullable
  abstract DateTime getLastTransferTime();

  @XmlElement(name = "authInfo")
  @Nullable
  abstract DomainAuthInfo getAuthInfo();

  /** Builder for {@link DomainInfoData}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setDomainName(String domainName);

    public abstract Builder setRepoId(String repoId);
    public abstract Builder setStatusValues(@Nullable ImmutableSet<StatusValue> statusValues);
    public abstract Builder setRegistrant(String registrant);
    public abstract Builder setContacts(
        @Nullable ImmutableSet<ForeignKeyedDesignatedContact> contacts);
    public abstract Builder setNameservers(@Nullable ImmutableSet<String> nameservers);
    public abstract Builder setSubordinateHosts(@Nullable ImmutableSet<String> subordinateHosts);

    public abstract Builder setCurrentSponsorRegistrarId(String currentSponsorRegistrarId);

    public abstract Builder setCreationRegistrarId(@Nullable String creationRegistrarId);

    public abstract Builder setCreationTime(@Nullable DateTime creationTime);

    public abstract Builder setLastEppUpdateRegistrarId(@Nullable String lastEppUpdateRegistrarId);

    public abstract Builder setLastEppUpdateTime(@Nullable DateTime lastEppUpdateTime);
    public abstract Builder setRegistrationExpirationTime(
        @Nullable DateTime registrationExpirationTime);
    public abstract Builder setLastTransferTime(@Nullable DateTime lastTransferTime);
    public abstract Builder setAuthInfo(@Nullable DomainAuthInfo authInfo);

    /** Internal accessor for use in {@link #build}. */
    @Nullable
    abstract ImmutableSet<String> getNameservers();

    /** Generated build method. */
    abstract DomainInfoData autoBuild();

    /** Public build method. */
    public DomainInfoData build() {
      // If there are no nameservers use null, or an empty "ns" element will be marshalled.
      setNameservers(forceEmptyToNull(getNameservers()));
      return autoBuild();
    }
  }

  public static Builder newBuilder() {
    return new AutoValue_DomainInfoData.Builder();
  }
}
