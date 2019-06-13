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

package google.registry.model.contact;

import com.google.auto.value.AutoValue;
import com.google.auto.value.AutoValue.CopyAnnotations;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.joda.time.DateTime;

/** The {@link ResponseData} returned for an EPP info flow on a contact. */
@XmlRootElement(name = "infData")
@XmlType(propOrder = {
    "contactId",
    "repoId",
    "statusValues",
    "postalInfos",
    "voiceNumber",
    "faxNumber",
    "emailAddress",
    "currentSponsorClientId",
    "creationClientId",
    "creationTime",
    "lastEppUpdateClientId",
    "lastEppUpdateTime",
    "lastTransferTime",
    "authInfo",
    "disclose" })
@AutoValue
@CopyAnnotations
public abstract class ContactInfoData implements ResponseData {

  @XmlElement(name = "id")
  abstract String getContactId();

  @XmlElement(name = "roid")
  abstract String getRepoId();

  @XmlElement(name = "status")
  abstract ImmutableSet<StatusValue> getStatusValues();

  @XmlElement(name = "postalInfo")
  abstract ImmutableList<PostalInfo> getPostalInfos();

  @XmlElement(name = "voice")
  @Nullable
  abstract ContactPhoneNumber getVoiceNumber();

  @XmlElement(name = "fax")
  @Nullable
  abstract ContactPhoneNumber getFaxNumber();

  @XmlElement(name = "email")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  @Nullable
  abstract String getEmailAddress();

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

  @XmlElement(name = "authInfo")
  @Nullable
  abstract ContactAuthInfo getAuthInfo();

  @XmlElement(name = "disclose")
  @Nullable
  abstract Disclose getDisclose();

  /** Builder for {@link ContactInfoData}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setContactId(String contactId);
    public abstract Builder setRepoId(String repoId);
    public abstract Builder setStatusValues(ImmutableSet<StatusValue> statusValues);
    public abstract Builder setPostalInfos(ImmutableList<PostalInfo> postalInfos);
    public abstract Builder setVoiceNumber(@Nullable ContactPhoneNumber voiceNumber);
    public abstract Builder setFaxNumber(@Nullable ContactPhoneNumber faxNumber);
    public abstract Builder setEmailAddress(@Nullable String emailAddress);
    public abstract Builder setCurrentSponsorClientId(String currentSponsorClientId);
    public abstract Builder setCreationClientId(String creationClientId);
    public abstract Builder setCreationTime(DateTime creationTime);
    public abstract Builder setLastEppUpdateClientId(@Nullable String lastEppUpdateClientId);
    public abstract Builder setLastEppUpdateTime(@Nullable DateTime lastEppUpdateTime);
    public abstract Builder setLastTransferTime(@Nullable DateTime lastTransferTime);
    public abstract Builder setAuthInfo(@Nullable ContactAuthInfo authInfo);
    public abstract Builder setDisclose(@Nullable Disclose disclose);
    public abstract ContactInfoData build();
  }

  public static Builder newBuilder() {
    return new AutoValue_ContactInfoData.Builder();
  }
}
