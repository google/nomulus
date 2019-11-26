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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.rde.RdeMode;
import google.registry.model.transfer.TransferData;
import google.registry.util.Idn;
import google.registry.xjc.domain.XjcDomainContactAttrType;
import google.registry.xjc.domain.XjcDomainContactType;
import google.registry.xjc.domain.XjcDomainNsType;
import google.registry.xjc.domain.XjcDomainStatusType;
import google.registry.xjc.domain.XjcDomainStatusValueType;
import google.registry.xjc.eppcom.XjcEppcomTrStatusType;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import google.registry.xjc.rdedomain.XjcRdeDomainTransferDataType;
import google.registry.xjc.rgp.XjcRgpStatusType;
import google.registry.xjc.rgp.XjcRgpStatusValueType;
import google.registry.xjc.secdns.XjcSecdnsDsDataType;
import google.registry.xjc.secdns.XjcSecdnsDsOrKeyType;

/** Utility class that turns {@link DomainBase} as {@link XjcRdeDomainElement}. */
final class DomainBaseToXjcConverter {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Converts {@link DomainBase} to {@link XjcRdeDomainElement}. */
  static XjcRdeDomainElement convert(DomainBase domain, RdeMode mode) {
    return new XjcRdeDomainElement(convertDomain(domain, mode));
  }

  /** Converts {@link DomainBase} to {@link XjcRdeDomain}. */
  static XjcRdeDomain convertDomain(DomainBase model, RdeMode mode) {
    XjcRdeDomain bean = new XjcRdeDomain();

    // o  A <name> element that contains the fully qualified name of the
    //    domain name object.
    bean.setName(model.getFullyQualifiedDomainName());

    // o  A <roid> element that contains the repository object identifier
    //    assigned to the domain name object when it was created.
    bean.setRoid(model.getRepoId());

    // o  An OPTIONAL <uName> element that contains the name of the domain
    //    name in Unicode character set.  It MUST be provided if available.
    bean.setUName(Idn.toUnicode(model.getFullyQualifiedDomainName()));

    // o  An OPTIONAL <idnTableId> element that references the IDN Table
    //    used for the IDN.  This corresponds to the "id" attribute of the
    //    <idnTableRef> element.  This element MUST be present if the domain
    //    name is an IDN.
    // ✭  We have to add some code to determine the IDN table id at creation
    //    time, then either save it somewhere, or else re-derive it here.
    bean.setIdnTableId(model.getIdnTableName());

    // o  An OPTIONAL <originalName> element is used to indicate that the
    //    domain name is an IDN variant.  This element contains the domain
    //    name used to generate the IDN variant.
    // ☠  Not relevant for now. We may do some bundling of variants in the
    //    future, but right now we're going to be doing blocking - which
    //    means we won't canonicalize the IDN name at the present time.
    // bean.setOriginalName(...);

    // o  A <clID> element that contains the identifier of the sponsoring
    //    registrar.
    bean.setClID(model.getCurrentSponsorClientId());

    // o  A <crRr> element that contains the identifier of the registrar
    //    that created the domain name object.  An OPTIONAL client attribute
    //    is used to specify the client that performed the operation.
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationClientId(), null));

    // o  An OPTIONAL <crDate> element that contains the date and time of
    //    the domain name object creation.  This element MUST be present if
    //    the domain name has been allocated.
    bean.setCrDate(model.getCreationTime());

    // o  An OPTIONAL <exDate> element that contains the date and time
    //    identifying the end (expiration) of the domain name object's
    //    registration period.  This element MUST be present if the domain
    //    name has been allocated.
    bean.setExDate(model.getRegistrationExpirationTime());

    // o  An OPTIONAL <upDate> element that contains the date and time of
    //    the most recent domain-name-object modification.  This element
    //    MUST NOT be present if the domain name object has never been
    //    modified.
    bean.setUpDate(model.getLastEppUpdateTime());

    // o  An OPTIONAL <upRr> element that contains the identifier of the
    //    registrar that last updated the domain name object.  This element
    //    MUST NOT be present if the domain has never been modified.  An
    //    OPTIONAL client attribute is used to specify the client that
    //    performed the operation.
    bean.setUpRr(RdeAdapter.convertRr(model.getLastEppUpdateClientId(), null));

    // o  An OPTIONAL <trDate> element that contains the date and time of
    //    the most recent domain object successful transfer.  This element
    //    MUST NOT be present if the domain name object has never been
    //    transferred.
    bean.setTrDate(model.getLastTransferTime());

    // o  One or more <status> elements that contain the current status
    //    descriptors associated with the domain name.
    for (StatusValue status : model.getStatusValues()) {
      bean.getStatuses().add(convertStatusValue(status));
    }

    // o  An OPTIONAL <ns> element that contains the fully qualified names
    //    of the delegated host objects or host attributes (name servers)
    //    associated with the domain name object.  See Section 1.1 of
    //    [RFC5731] for a description of the elements used to specify host
    //    objects or host attributes.
    // ✭  We don't support host attributes, only host objects. The RFC says
    //    you have to support one or the other, but not both. The gist of
    //    it is that with host attributes, you inline the nameserver data
    //    on each domain; with host objects, you normalize the nameserver
    //    data to a separate EPP object.
    ImmutableSet<String> linkedNameserverHostNames = model.loadNameserverFullyQualifiedHostNames();
    if (!linkedNameserverHostNames.isEmpty()) {
      XjcDomainNsType nameservers = new XjcDomainNsType();
      for (String hostName : linkedNameserverHostNames) {
        nameservers.getHostObjs().add(hostName);
      }
      bean.setNs(nameservers);
    }

    switch (mode) {
      case FULL:
        String domainName = model.getFullyQualifiedDomainName();

        // o  Zero or more OPTIONAL <rgpStatus> element to represent
        //    "pendingDelete" sub-statuses, including "redemptionPeriod",
        //    "pendingRestore", and "pendingDelete", that a domain name can be
        //    in as a result of grace period processing as specified in
        //    [RFC3915].
        for (GracePeriodStatus status : model.getGracePeriodStatuses()) {
          bean.getRgpStatuses().add(convertGracePeriodStatus(status));
        }

        // o  An OPTIONAL <registrant> element that contain the identifier for
        //    the human or organizational social information object associated
        //    as the holder of the domain name object.
        Key<ContactResource> registrant = model.getRegistrant();
        if (registrant == null) {
          logger.atWarning().log("Domain %s has no registrant contact.", domainName);
        } else {
          ContactResource registrantContact = ofy().load().key(registrant).now();
          checkState(
              registrantContact != null,
              "Registrant contact %s on domain %s does not exist",
              registrant,
              domainName);
          bean.setRegistrant(registrantContact.getContactId());
        }

        // o  Zero or more OPTIONAL <contact> elements that contain identifiers
        //    for the human or organizational social information objects
        //    associated with the domain name object.
        for (DesignatedContact contact : model.getContacts()) {
          bean.getContacts().add(convertDesignatedContact(contact, domainName));
        }

        // o  An OPTIONAL <secDNS> element that contains the public key
        //    information associated with Domain Name System security (DNSSEC)
        //    extensions for the domain name as specified in [RFC5910].
        // ☠  We don't set keyData because we use dsData. The RFCs offer us a
        //    choice between the two, similar to hostAttr vs. hostObj above.
        // ☠  We're not going to support maxSigLife since it seems to be
        //    completely useless.
        if (!model.getDsData().isEmpty()) {
          XjcSecdnsDsOrKeyType secdns = new XjcSecdnsDsOrKeyType();
          for (DelegationSignerData ds : model.getDsData()) {
            secdns.getDsDatas().add(convertDelegationSignerData(ds));
          }
          bean.setSecDNS(secdns);
        }

        // o  An OPTIONAL <trnData> element that contains the following child
        //    elements related to the last transfer request of the domain name
        //    object.  This element MUST NOT be present if a transfer request
        //    for the domain name has never been created.
        //
        //    *  A <trStatus> element that contains the state of the most recent
        //       transfer request.
        //
        //    *  A <reRr> element that contains the identifier of the registrar
        //       that requested the domain name object transfer.  An OPTIONAL
        //       client attribute is used to specify the client that performed
        //       the operation.
        //
        //    *  A <reDate> element that contains the date and time that the
        //       transfer was requested.
        //
        //    *  An <acRr> element that contains the identifier of the registrar
        //       that SHOULD act upon a PENDING transfer request.  For all other
        //       status types, the value identifies the registrar that took the
        //       indicated action.  An OPTIONAL client attribute is used to
        //       specify the client that performed the operation.
        //
        //    *  An <acDate> element that contains the date and time of a
        //       required or completed response.  For a PENDING request, the
        //       value identifies the date and time by which a response is
        //       required before an automated response action will be taken by
        //       the registry.  For all other status types, the value identifies
        //       the date and time when the request was completed.
        //
        //    *  An OPTIONAL <exDate> element that contains the end of the
        //       domain name object's validity period (expiry date) if the
        //       transfer caused or causes a change in the validity period.
        if (!model.getTransferData().equals(TransferData.EMPTY)) {
          // Temporary check to make sure that there really was a transfer. A bug caused spurious
          // empty transfer records to get generated for deleted domains.
          // TODO(b/33289763): remove the hasGainingAndLosingRegistrars check in February 2017
          if (hasGainingAndLosingRegistrars(model)) {
            bean.setTrnData(convertTransferData(model.getTransferData()));
          }
        }

        break;
      case THIN:
        break;
    }

    return bean;
  }

  private static boolean hasGainingAndLosingRegistrars(DomainBase model) {
    return
        !Strings.isNullOrEmpty(model.getTransferData().getGainingClientId())
        && !Strings.isNullOrEmpty(model.getTransferData().getLosingClientId());
  }

  /** Converts {@link TransferData} to {@link XjcRdeDomainTransferDataType}. */
  private static XjcRdeDomainTransferDataType convertTransferData(TransferData model) {
    XjcRdeDomainTransferDataType bean = new XjcRdeDomainTransferDataType();
    bean.setTrStatus(
        XjcEppcomTrStatusType.fromValue(model.getTransferStatus().getXmlName()));
    bean.setReRr(RdeUtil.makeXjcRdeRrType(model.getGainingClientId()));
    bean.setAcRr(RdeUtil.makeXjcRdeRrType(model.getLosingClientId()));
    bean.setReDate(model.getTransferRequestTime());
    bean.setAcDate(model.getPendingTransferExpirationTime());
    bean.setExDate(model.getTransferredRegistrationExpirationTime());
    return bean;
  }

  /** Converts {@link GracePeriodStatus} to {@link XjcRgpStatusType}. */
  private static XjcRgpStatusType convertGracePeriodStatus(GracePeriodStatus model) {
    XjcRgpStatusType bean = new XjcRgpStatusType();
    bean.setS(XjcRgpStatusValueType.fromValue(model.getXmlName()));
    return bean;
  }

  /** Converts {@link StatusValue} to {@link XjcDomainStatusType}. */
  private static XjcDomainStatusType convertStatusValue(StatusValue model) {
    XjcDomainStatusType bean = new XjcDomainStatusType();
    bean.setS(XjcDomainStatusValueType.fromValue(model.getXmlName()));
    return bean;
  }

  /** Converts {@link DelegationSignerData} to {@link XjcSecdnsDsDataType}. */
  private static XjcSecdnsDsDataType convertDelegationSignerData(DelegationSignerData model) {
    XjcSecdnsDsDataType bean = new XjcSecdnsDsDataType();
    bean.setKeyTag(model.getKeyTag());
    bean.setAlg((short) model.getAlgorithm());
    bean.setDigestType((short) model.getDigestType());
    bean.setDigest(model.getDigest());
    bean.setKeyData(null);
    return bean;
  }

  /** Converts {@link DesignatedContact} to {@link XjcDomainContactType}. */
  private static XjcDomainContactType convertDesignatedContact(
      DesignatedContact model, String domainName) {
    XjcDomainContactType bean = new XjcDomainContactType();
    checkState(
        model.getContactKey() != null,
        "Contact key for type %s is null on domain %s",
        model.getType(),
        domainName);
    ContactResource contact = ofy().load().key(model.getContactKey()).now();
    checkState(
        contact != null,
        "Contact %s on domain %s does not exist",
        model.getContactKey(),
        domainName);
    bean.setType(XjcDomainContactAttrType.fromValue(Ascii.toLowerCase(model.getType().toString())));
    bean.setValue(contact.getContactId());
    return bean;
  }

  private DomainBaseToXjcConverter() {}
}
