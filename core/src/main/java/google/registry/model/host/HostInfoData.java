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

import com.google.auto.value.AutoValue;
import com.google.auto.value.AutoValue.CopyAnnotations;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import java.net.InetAddress;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.joda.time.DateTime;

/** The {@link ResponseData} returned for an EPP info flow on a host. */
@XmlRootElement(name = "infData")
@XmlType(propOrder = {
    "fullyQualifiedHostName",
    "repoId",
    "statusValues",
    "inetAddresses",
    "currentSponsorClientId",
    "creationClientId",
    "creationTime",
    "lastEppUpdateClientId",
    "lastEppUpdateTime",
    "lastTransferTime" })
@AutoValue
@CopyAnnotations
public abstract class HostInfoData implements ResponseData {

  @XmlElement(name = "name")
  abstract String getFullyQualifiedHostName();

  @XmlElement(name = "roid")
  abstract String getRepoId();

  @XmlElement(name = "status")
  abstract ImmutableSet<StatusValue> getStatusValues();

  @XmlElement(name = "addr")
  abstract ImmutableSet<InetAddress> getInetAddresses();

  @XmlElement(name = "clID")
  abstract String getCurrentSponsorClientId();

  @XmlElement(name = "crID")
  abstract String getCreationClientId();

  @XmlElement(name = "crDate")
  abstract DateTime getCreationTime();

  @XmlElement(name = "upID")
  @Nullable
  abstract String getLastEppUpdateClientId();

  @XmlElement(name = "upDate")
  @Nullable
  abstract DateTime getLastEppUpdateTime();

  @XmlElement(name = "trDate")
  @Nullable
  abstract DateTime getLastTransferTime();

  /** Builder for {@link HostInfoData}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setFullyQualifiedHostName(String fullyQualifiedHostName);
    public abstract Builder setRepoId(String repoId);
    public abstract Builder setStatusValues(ImmutableSet<StatusValue> statusValues);
    public abstract Builder setInetAddresses(ImmutableSet<InetAddress> inetAddresses);
    public abstract Builder setCurrentSponsorClientId(String currentSponsorClientId);
    public abstract Builder setCreationClientId(String creationClientId);
    public abstract Builder setCreationTime(DateTime creationTime);
    public abstract Builder setLastEppUpdateClientId(@Nullable String lastEppUpdateClientId);
    public abstract Builder setLastEppUpdateTime(@Nullable DateTime lastEppUpdateTime);
    public abstract Builder setLastTransferTime(@Nullable DateTime lastTransferTime);
    public abstract HostInfoData build();
  }

  public static Builder newBuilder() {
    return new AutoValue_HostInfoData.Builder();
  }
}
