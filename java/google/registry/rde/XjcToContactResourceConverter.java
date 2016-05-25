// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.rde;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.Disclose.PostalInfoChoice;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.XmlEnumUtils.XmlToEnumMapper;
import google.registry.xjc.contact.XjcContactAddrType;
import google.registry.xjc.contact.XjcContactDiscloseType;
import google.registry.xjc.contact.XjcContactE164Type;
import google.registry.xjc.contact.XjcContactIntLocType;
import google.registry.xjc.contact.XjcContactPostalInfoEnumType;
import google.registry.xjc.contact.XjcContactPostalInfoType;
import google.registry.xjc.contact.XjcContactStatusType;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactTransferDataType;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/** Utility class that turns {@link XjcRdeContact} as {@link ContactResource}. */
final class XjcToContactResourceConverter {

  private static final XmlToEnumMapper<PostalInfo.Type> postalInfoTypeMapper =
      new XmlToEnumMapper<>(PostalInfo.Type.values());
  private static final XmlToEnumMapper<TransferStatus> transferStatusMapper =
      new XmlToEnumMapper<>(TransferStatus.values());

  //TODO: use lambda syntax when java 8 syntax is available
  private static final Function<XjcContactIntLocType, PostalInfoChoice> choiceConverter =
      new Function<XjcContactIntLocType, PostalInfoChoice>() {
        public PostalInfoChoice apply(XjcContactIntLocType choice) {
          return convertPostalInfoChoice(choice);
        }
      };

  private static final Function<XjcContactStatusType, StatusValue> statusConverter =
      new Function<XjcContactStatusType, StatusValue>() {
        public StatusValue apply(XjcContactStatusType status) {
          return convertStatusValue(status);
        }

  };

  /** Converts {@link XjcRdeContact} to {@link ContactResource}. */
  static ContactResource convertContact(XjcRdeContact contact) {
    return new ContactResource.Builder()
        .setRepoId(contact.getRoid())
        .setStatusValues(
            ImmutableSet.copyOf(Iterables.transform(contact.getStatuses(), statusConverter)))
        .setLocalizedPostalInfo(
            getPostalInfoOfType(contact.getPostalInfos(), XjcContactPostalInfoEnumType.LOC))
        .setInternationalizedPostalInfo(
            getPostalInfoOfType(contact.getPostalInfos(), XjcContactPostalInfoEnumType.INT))
        .setContactId(contact.getId())
        .setCurrentSponsorClientId(contact.getClID())
        .setCreationClientId(contact.getCrRr() == null ? null : contact.getCrRr().getValue())
        .setLastEppUpdateClientId(contact.getUpRr() == null ? null : contact.getUpRr().getValue())
        .setCreationTime(contact.getCrDate())
        .setLastEppUpdateTime(contact.getUpDate())
        .setLastTransferTime(contact.getTrDate())
        .setVoiceNumber(convertPhoneNumber(contact.getVoice()))
        .setFaxNumber(convertPhoneNumber(contact.getFax()))
        .setEmailAddress(contact.getEmail())
        .setDisclose(convertDisclose(contact.getDisclose()))
        .setTransferData(convertTransferData(contact.getTrnData()))
        .build();
  }

  /**
   * Extracts a {@link PostalInfo} from an {@link Iterable} of {@link XjcContactPostalInfoEnumType}.
   */
  @Nullable
  private static PostalInfo getPostalInfoOfType(
      Iterable<XjcContactPostalInfoType> postalInfos, XjcContactPostalInfoEnumType type) {
    for (XjcContactPostalInfoType postalInfo : postalInfos) {
      if (postalInfo.getType() == type) {
        return convertPostalInfo(postalInfo);
      }
    }
    return null;
  }

  /** Converts {@link XjcRdeContactTransferDataType} to {@link TransferData}. */
  @CheckForNull
  private static TransferData convertTransferData(XjcRdeContactTransferDataType transferData) {
    if (transferData == null) {
      return TransferData.EMPTY;
    }
    return new TransferData.Builder()
        .setTransferStatus(transferStatusMapper.xmlToEnum(transferData.getTrStatus().value()))
        .setGainingClientId(transferData.getReRr().getValue())
        .setLosingClientId(transferData.getAcRr().getValue())
        .setTransferRequestTime(transferData.getReDate())
        .setPendingTransferExpirationTime(transferData.getAcDate())
        .build();
  }

  /** Converts {@link XjcContactAddrType} to {@link ContactAddress}. */
  private static ContactAddress convertAddress(XjcContactAddrType address) {
    return new ContactAddress.Builder()
        .setStreet(ImmutableList.copyOf(address.getStreets()))
        .setCity(address.getCity())
        .setState(address.getSp())
        .setZip(address.getPc())
        .setCountryCode(address.getCc())
        .build();
  }

  /** Converts {@link XjcContactDiscloseType} to {@link Disclose}. */
  @Nullable
  @CheckForNull
  private static Disclose convertDisclose(XjcContactDiscloseType disclose) {
    if (disclose == null) {
      return null;
    }
    return new Disclose.Builder()
        .setFlag(disclose.isFlag())
        .setNames(ImmutableList.copyOf(Lists.transform(disclose.getNames(), choiceConverter)))
        .setOrgs(ImmutableList.copyOf(Lists.transform(disclose.getOrgs(), choiceConverter)))
        .setAddrs(ImmutableList.copyOf(Lists.transform(disclose.getAddrs(), choiceConverter)))
        .build();
  }

  /** Converts {@link XjcContactE164Type} to {@link ContactPhoneNumber}. */
  @Nullable
  @CheckForNull
  private static ContactPhoneNumber convertPhoneNumber(@Nullable XjcContactE164Type phoneNumber) {
    if (phoneNumber == null) {
      return null;
    }
    return new ContactPhoneNumber.Builder()
        .setPhoneNumber(phoneNumber.getValue())
        .setExtension(phoneNumber.getX())
        .build();
  }

  /** Converts {@link PostalInfoChoice} to {@link XjcContactIntLocType}. */
  private static PostalInfoChoice convertPostalInfoChoice(XjcContactIntLocType choice) {
    return PostalInfoChoice.create(postalInfoTypeMapper.xmlToEnum(choice.getType().value()));
  }

  /** Converts {@link XjcContactPostalInfoType} to {@link PostalInfo}. */
  private static PostalInfo convertPostalInfo(XjcContactPostalInfoType postalInfo) {
    return new PostalInfo.Builder()
        .setName(postalInfo.getName())
        .setOrg(postalInfo.getOrg())
        .setAddress(convertAddress(postalInfo.getAddr()))
        .setType(postalInfoTypeMapper.xmlToEnum(postalInfo.getType().value()))
        .build();
  }

  /** Converts {@link XjcContactStatusType} to {@link StatusValue}. */
  private static StatusValue convertStatusValue(XjcContactStatusType statusType) {
    return StatusValue.fromXmlName(statusType.getS().value());
  }

  private XjcToContactResourceConverter() {}
}
