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

package google.registry.rde;

import static google.registry.util.XmlEnumUtils.enumToXml;

import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.Disclose.PostalInfoChoice;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferData;
import google.registry.xjc.contact.XjcContactAddrType;
import google.registry.xjc.contact.XjcContactDiscloseType;
import google.registry.xjc.contact.XjcContactE164Type;
import google.registry.xjc.contact.XjcContactIntLocType;
import google.registry.xjc.contact.XjcContactPostalInfoEnumType;
import google.registry.xjc.contact.XjcContactPostalInfoType;
import google.registry.xjc.contact.XjcContactStatusType;
import google.registry.xjc.contact.XjcContactStatusValueType;
import google.registry.xjc.eppcom.XjcEppcomTrStatusType;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactElement;
import google.registry.xjc.rdecontact.XjcRdeContactTransferDataType;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/** Utility class that turns {@link ContactResource} as {@link XjcRdeContactElement}. */
final class ContactResourceToXjcConverter {

  /** Converts {@link ContactResource} to {@link XjcRdeContactElement}. */
  static XjcRdeContactElement convert(ContactResource host) {
    return new XjcRdeContactElement(convertContact(host));
  }

  /** Converts {@link ContactResource} to {@link XjcRdeContact}. */
  static XjcRdeContact convertContact(ContactResource model) {
    XjcRdeContact bean = new XjcRdeContact();
    bean.setRoid(model.getRepoId());
    for (StatusValue status : model.getStatusValues()) {
      bean.getStatuses().add(convertStatusValue(status));
    }
    PostalInfo localizedPostalInfo = model.getLocalizedPostalInfo();
    if (localizedPostalInfo != null) {
      bean.getPostalInfos().add(convertPostalInfo(localizedPostalInfo));
    }
    PostalInfo internationalizedPostalInfo = model.getInternationalizedPostalInfo();
    if (internationalizedPostalInfo != null) {
      bean.getPostalInfos().add(convertPostalInfo(internationalizedPostalInfo));
    }
    bean.setId(model.getContactId());
    bean.setClID(model.getCurrentSponsorRegistrarId());
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationRegistrarId(), null));
    bean.setUpRr(RdeAdapter.convertRr(model.getLastEppUpdateRegistrarId(), null));
    bean.setCrDate(model.getCreationTime());
    bean.setUpDate(model.getLastEppUpdateTime());
    bean.setTrDate(model.getLastTransferTime());
    bean.setVoice(convertPhoneNumber(model.getVoiceNumber()));
    bean.setFax(convertPhoneNumber(model.getFaxNumber()));
    bean.setEmail(model.getEmailAddress());
    bean.setDisclose(convertDisclose(model.getDisclose()));

    // o  An OPTIONAL <trnData> element that contains the following child
    //    elements related to the last transfer request of the contact
    //    object:
    //
    //    *  A <trStatus> element that contains the state of the most recent
    //       transfer request.
    //
    //    *  A <reRr> element that contains the identifier of the registrar
    //       that requested the domain name object transfer.  An OPTIONAL
    //       client attribute is used to specify the client that performed
    //       the operation.
    //
    //    *  An <acRr> element that contains the identifier of the registrar
    //       that SHOULD act upon a PENDING transfer request.  For all other
    //       status types, the value identifies the registrar that took the
    //       indicated action.  An OPTIONAL client attribute is used to
    //       specify the client that performed the operation.
    //
    //    *  A <reDate> element that contains the date and time that the
    //       transfer was requested.
    //
    //    *  An <acDate> element that contains the date and time of a
    //       required or completed response.  For a PENDING request, the
    //       value identifies the date and time by which a response is
    //       required before an automated response action will be taken by
    //       the registry.  For all other status types, the value identifies
    //       the date and time when the request was completed.
    if (!model.getTransferData().isEmpty()) {
      bean.setTrnData(convertTransferData(model.getTransferData()));
    }

    return bean;
  }

  /** Converts {@link TransferData} to {@link XjcRdeContactTransferDataType}. */
  private static XjcRdeContactTransferDataType convertTransferData(TransferData model) {
    XjcRdeContactTransferDataType bean = new XjcRdeContactTransferDataType();
    bean.setTrStatus(XjcEppcomTrStatusType.fromValue(model.getTransferStatus().getXmlName()));
    bean.setReRr(RdeUtil.makeXjcRdeRrType(model.getGainingRegistrarId()));
    bean.setAcRr(RdeUtil.makeXjcRdeRrType(model.getLosingRegistrarId()));
    bean.setReDate(model.getTransferRequestTime());
    bean.setAcDate(model.getPendingTransferExpirationTime());
    return bean;
  }

  /** Converts {@link ContactAddress} to {@link XjcContactAddrType}. */
  private static XjcContactAddrType convertAddress(ContactAddress model) {
    XjcContactAddrType bean = new XjcContactAddrType();
    bean.getStreets().addAll(model.getStreet());
    bean.setCity(model.getCity());
    bean.setSp(model.getState());
    bean.setPc(model.getZip());
    bean.setCc(model.getCountryCode());
    return bean;
  }

  /** Converts {@link Disclose} to {@link XjcContactDiscloseType}. */
  @Nullable
  @CheckForNull
  static XjcContactDiscloseType convertDisclose(@Nullable Disclose model) {
    if (model == null) {
      return null;
    }
    XjcContactDiscloseType bean = new XjcContactDiscloseType();
    bean.setFlag(model.getFlag());
    for (PostalInfoChoice loc : model.getNames()) {
      bean.getNames().add(convertPostalInfoChoice(loc));
    }
    for (PostalInfoChoice loc : model.getOrgs()) {
      bean.getOrgs().add(convertPostalInfoChoice(loc));
    }
    for (PostalInfoChoice loc : model.getAddrs()) {
      bean.getAddrs().add(convertPostalInfoChoice(loc));
    }
    return bean;
  }

  /** Converts {@link ContactPhoneNumber} to {@link XjcContactE164Type}. */
  @Nullable
  @CheckForNull
  private static XjcContactE164Type convertPhoneNumber(@Nullable ContactPhoneNumber model) {
    if (model == null) {
      return null;
    }
    XjcContactE164Type bean = new XjcContactE164Type();
    bean.setValue(model.getPhoneNumber());
    bean.setX(model.getExtension());
    return bean;
  }

  /** Converts {@link PostalInfoChoice} to {@link XjcContactIntLocType}. */
  private static XjcContactIntLocType convertPostalInfoChoice(PostalInfoChoice model) {
    XjcContactIntLocType bean = new XjcContactIntLocType();
    bean.setType(XjcContactPostalInfoEnumType.fromValue(enumToXml(model.getType())));
    return bean;
  }

  /** Converts {@link PostalInfo} to {@link XjcContactPostalInfoType}. */
  private static XjcContactPostalInfoType convertPostalInfo(PostalInfo model) {
    XjcContactPostalInfoType bean = new XjcContactPostalInfoType();
    bean.setName(model.getName());
    bean.setOrg(model.getOrg());
    bean.setAddr(convertAddress(model.getAddress()));
    bean.setType(XjcContactPostalInfoEnumType.fromValue(enumToXml(model.getType())));
    return bean;
  }

  /** Converts {@link StatusValue} to {@link XjcContactStatusType}. */
  private static XjcContactStatusType convertStatusValue(StatusValue model) {
    XjcContactStatusType bean = new XjcContactStatusType();
    bean.setS(XjcContactStatusValueType.fromValue(model.getXmlName()));
    return bean;
  }

  private ContactResourceToXjcConverter() {}
}
