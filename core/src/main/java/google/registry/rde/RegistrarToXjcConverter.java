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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.xjc.contact.XjcContactE164Type;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarAddrType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarElement;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarPostalInfoEnumType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarPostalInfoType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarStatusType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarWhoisInfoType;
import java.math.BigInteger;

/** Utility class that turns {@link Registrar} as {@link XjcRdeRegistrarElement}. */
final class RegistrarToXjcConverter {

  private static final String UNKNOWN_EMAIL = "unknown@crr.com";
  private static final String UNKNOWN_CITY = "Unknown";
  private static final String UNKNOWN_ZIP = "00000";
  private static final String UNKNOWN_CC = "US";

  /** A conversion map between internal Registrar states and external RDE states. */
  private static final ImmutableMap<Registrar.State, XjcRdeRegistrarStatusType>
      REGISTRAR_STATUS_CONVERSIONS =
          ImmutableMap.of(
              State.ACTIVE, XjcRdeRegistrarStatusType.OK,
              State.PENDING, XjcRdeRegistrarStatusType.READONLY,
              State.SUSPENDED, XjcRdeRegistrarStatusType.READONLY,
              State.DISABLED, XjcRdeRegistrarStatusType.TERMINATED);

  /** Converts {@link Registrar} to {@link XjcRdeRegistrarElement}. */
  static XjcRdeRegistrarElement convert(Registrar registrar) {
    return new XjcRdeRegistrarElement(convertRegistrar(registrar));
  }

  /** Converts {@link Registrar} to {@link XjcRdeRegistrar}. */
  static XjcRdeRegistrar convertRegistrar(Registrar model) {
    XjcRdeRegistrar bean = new XjcRdeRegistrar();

    // o  An <id> element that contains the Registry-unique identifier of
    //    the registrar object.  This <id> has a superordinate relationship
    //    to a subordinate <clID>, <crRr> or <upRr> of domain, contact and
    //    host objects.
    bean.setId(model.getRegistrarId());

    // o  An <name> element that contains the name of the registrar.
    bean.setName(model.getRegistrarName());

    // o  An OPTIONAL <gurid> element that contains the ID assigned by
    //    ICANN.
    Long ianaId = model.getIanaIdentifier();
    if (ianaId != null) {
      bean.setGurid(BigInteger.valueOf(ianaId));
    }

    // o  A <status> element that contains the operational status of the
    //    registrar.  Possible values are: ok, readonly and terminated.
    checkState(
        REGISTRAR_STATUS_CONVERSIONS.containsKey(model.getState()),
        "Unknown registrar state: %s",
        model.getState());
    bean.setStatus(REGISTRAR_STATUS_CONVERSIONS.get(model.getState()));

    // o  One or two <postalInfo> elements that contain postal- address
    //    information.  Two elements are provided so that address
    //    information can be provided in both internationalized and
    //    localized forms; a "type" attribute is used to identify the two
    //    forms.  If an internationalized form (type="int") is provided,
    //    element content MUST be represented in a subset of UTF-8 that can
    //    be represented in the 7-bit US-ASCII character set.  If a
    //    localized form (type="loc") is provided, element content MAY be
    //    represented in unrestricted UTF-8.
    RegistrarAddress localizedAddress = model.getLocalizedAddress();
    if (localizedAddress != null) {
      bean.getPostalInfos().add(convertPostalInfo(false, localizedAddress));
    }
    RegistrarAddress internationalizedAddress = model.getInternationalizedAddress();
    if (internationalizedAddress != null) {
      bean.getPostalInfos().add(convertPostalInfo(true, internationalizedAddress));
    }

    // o  An OPTIONAL <voice> element that contains the registrar's voice
    //    telephone number.
    // XXX: Make Registrar use PhoneNumber.
    if (model.getPhoneNumber() != null) {
      XjcContactE164Type phone = new XjcContactE164Type();
      phone.setValue(model.getPhoneNumber());
      bean.setVoice(phone);
    }

    // o  An OPTIONAL <fax> element that contains the registrar's facsimile
    //    telephone number.
    if (model.getFaxNumber() != null) {
      XjcContactE164Type fax = new XjcContactE164Type();
      fax.setValue(model.getFaxNumber());
      bean.setFax(fax);
    }

    // o  An <email> element that contains the registrar's email address.
    bean.setEmail(firstNonNull(model.getEmailAddress(), UNKNOWN_EMAIL));

    // o  An OPTIONAL <url> element that contains the registrar's URL.
    bean.setUrl(model.getUrl());

    // o  An OPTIONAL <whoisInfo> elements that contains whois information.
    //    The <whoisInfo> element contains the following child elements:
    //
    //    *  An OPTIONAL <name> element that contains the name of the
    //       registrar WHOIS server listening on TCP port 43 as specified in
    //       [RFC3912].
    //
    //    *  An OPTIONAL <url> element that contains the name of the
    //       registrar WHOIS server listening on TCP port 80/443.
    if (model.getWhoisServer() != null) {
      XjcRdeRegistrarWhoisInfoType whoisInfo = new XjcRdeRegistrarWhoisInfoType();
      whoisInfo.setName(model.getWhoisServer());
      bean.setWhoisInfo(whoisInfo);
    }

    // o  A <crDate> element that contains the date and time of registrar-
    //    object creation.
    bean.setCrDate(model.getCreationTime());

    // o  An OPTIONAL <upDate> element that contains the date and time of
    //    the most recent RDE registrar-object modification.  This element
    //    MUST NOT be present if the rdeRegistrar object has never been
    //    modified.
    bean.setUpDate(model.getLastUpdateTime());

    return bean;
  }

  private static XjcRdeRegistrarPostalInfoType convertPostalInfo(
      boolean isInt, RegistrarAddress model) {
    XjcRdeRegistrarPostalInfoType bean = new XjcRdeRegistrarPostalInfoType();
    bean.setType(isInt
        ? XjcRdeRegistrarPostalInfoEnumType.INT
        : XjcRdeRegistrarPostalInfoEnumType.LOC);
    bean.setAddr(convertAddress(model));
    return bean;
  }

  private static XjcRdeRegistrarAddrType convertAddress(RegistrarAddress model) {
    XjcRdeRegistrarAddrType bean = new XjcRdeRegistrarAddrType();

    // *  A <addr> element that contains address information associated
    //    with the registrar.  The <addr> element contains the following
    //    child elements:
    //
    //    +  One, two, or three OPTIONAL <street> elements that contain
    //       the registrar's street address.
    bean.getStreets().addAll(model.getStreet());

    //    +  A <city> element that contains the registrar's city.
    bean.setCity(firstNonNull(model.getCity(), UNKNOWN_CITY));

    //    +  An OPTIONAL <sp> element that contains the registrar's state
    //       or province.
    bean.setSp(model.getState());

    //    +  An OPTIONAL <pc> element that contains the registrar's
    //       postal code.
    bean.setPc(firstNonNull(model.getZip(), UNKNOWN_ZIP));

    //    +  A <cc> element that contains the registrar's country code.
    bean.setCc(firstNonNull(model.getCountryCode(), UNKNOWN_CC));

    return bean;
  }

  private RegistrarToXjcConverter() {}
}
