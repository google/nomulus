// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde.imports;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.transform;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registries;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.XmlToEnumMapper;
import google.registry.xjc.domain.XjcDomainContactType;
import google.registry.xjc.domain.XjcDomainNsType;
import google.registry.xjc.domain.XjcDomainStatusType;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import google.registry.xjc.rdedomain.XjcRdeDomainTransferDataType;
import google.registry.xjc.rgp.XjcRgpStatusType;
import google.registry.xjc.secdns.XjcSecdnsDsDataType;
import org.joda.time.DateTime;

/** Utility class that converts an {@link XjcRdeDomainElement} into a {@link DomainResource}. */
final class XjcToDomainResourceConverter extends XjcToEppResourceConverter {

  private static final XmlToEnumMapper<TransferStatus> TRANSFER_STATUS_MAPPER =
      XmlToEnumMapper.create(TransferStatus.values());

  private static final Function<XjcDomainStatusType, StatusValue> STATUS_CONVERTER =
      new Function<XjcDomainStatusType, StatusValue>() {
        @Override
        public StatusValue apply(XjcDomainStatusType status) {
          return convertStatusType(status);
        }
      };

  private static final Function<String, Key<HostResource>> HOST_OBJ_CONVERTER =
      new Function<String, Key<HostResource>>() {
        @Override
        public Key<HostResource> apply(String fullyQualifiedHostName) {
          Key<HostResource> key =
              ForeignKeyIndex.loadAndGetKey(
                  HostResource.class, fullyQualifiedHostName, DateTime.now());
          checkState(
              key != null,
              String.format("HostResource not found with name '%s'", fullyQualifiedHostName));
          return key;
        }
      };

  private static final Function<XjcDomainContactType, DesignatedContact> CONTACT_CONVERTER =
      new Function<XjcDomainContactType, DesignatedContact>() {
        @Override
        public DesignatedContact apply(XjcDomainContactType contact) {
          return convertContactType(contact);
        }
      };

  private static final Function<XjcSecdnsDsDataType, DelegationSignerData> SECDNS_CONVERTER =
      new Function<XjcSecdnsDsDataType, DelegationSignerData>() {
        @Override
        public DelegationSignerData apply(XjcSecdnsDsDataType secdns) {
          return convertSecdnsDsDataType(secdns);
        }
      };

  /** Converts {@link XjcRgpStatusType} to {@link GracePeriod} */
  private static class GracePeriodConverter implements Function<XjcRgpStatusType, GracePeriod> {

    private final XjcRdeDomain domain;
    private final Key<BillingEvent.Recurring> autoRenewBillingEvent;
    private final Registry tld;

    GracePeriodConverter(XjcRdeDomain domain, Key<BillingEvent.Recurring> autoRenewBillingEvent) {
      this.domain = domain;
      this.autoRenewBillingEvent = autoRenewBillingEvent;
      this.tld =
          Registry.get(
              Registries.findTldForNameOrThrow(InternetDomainName.from(domain.getName()))
                  .toString());
    }

    @Override
    public GracePeriod apply(XjcRgpStatusType gracePeriodStatus) {
      // TODO: (wolfgang) address these items in code review:
      // verify that this logic is correct
      switch (gracePeriodStatus.getS()) {
        case ADD_PERIOD:
          return GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.ADD,
              domain.getCrDate().plus(this.tld.getAddGracePeriodLength()),
              domain.getCrRr().getClient());
        case AUTO_RENEW_PERIOD:
          return GracePeriod.createForRecurring(
              GracePeriodStatus.AUTO_RENEW,
              domain.getUpDate().plus(this.tld.getAutoRenewGracePeriodLength()),
              domain.getClID(),
              autoRenewBillingEvent);
        case PENDING_DELETE:
          return GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.PENDING_DELETE,
              domain.getUpDate().plus(this.tld.getPendingDeleteLength()),
              domain.getClID());
        case REDEMPTION_PERIOD:
          return GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.REDEMPTION,
              domain.getUpDate().plus(this.tld.getRedemptionGracePeriodLength()),
              domain.getClID());
        case RENEW_PERIOD:
          return GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.RENEW,
              domain.getUpDate().plus(this.tld.getRenewGracePeriodLength()),
              domain.getClID());
        case TRANSFER_PERIOD:
          return GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.TRANSFER,
              domain.getUpDate().plus(this.tld.getTransferGracePeriodLength()),
              domain.getTrnData().getReRr().getValue());
        default:
          throw new IllegalArgumentException(
              "Unsupported grace period status: " + gracePeriodStatus.getS());
      }
    }
  }

  /** Converts {@link XjcRdeDomain} to {@link DomainResource}. */
  static DomainResource convertDomain(XjcRdeDomain domain) {
    // First create history entry and autorenew billing event/poll message
    // Autorenew billing event is required for creating AUTO_RENEW grace period
    HistoryEntry historyEntry = createHistoryEntry(domain);
    BillingEvent.Recurring autoRenewBillingEvent =
        createAutoRenewBillingEvent(domain, historyEntry);
    PollMessage.Autorenew pollMessage = createAutoRenewPollMessage(domain, historyEntry);
    ofy().save().<ImmutableObject>entities(historyEntry, autoRenewBillingEvent, pollMessage);
    GracePeriodConverter gracePeriodConverter =
        new GracePeriodConverter(domain, Key.create(autoRenewBillingEvent));
    DomainResource.Builder builder =
        new DomainResource.Builder()
            .setFullyQualifiedDomainName(domain.getName())
            .setRepoId(domain.getRoid())
            .setIdnTableName(domain.getIdnTableId())
            .setCurrentSponsorClientId(domain.getClID())
            .setCreationClientId(domain.getCrRr().getValue())
            .setCreationTime(domain.getCrDate())
            .setRegistrationExpirationTime(domain.getExDate())
            .setLastEppUpdateTime(domain.getUpDate())
            .setLastEppUpdateClientId(domain.getUpRr() == null ? null : domain.getUpRr().getValue())
            .setLastTransferTime(domain.getTrDate())
            .setStatusValues(ImmutableSet.copyOf(transform(domain.getStatuses(), STATUS_CONVERTER)))
            .setNameservers(convertNameservers(domain.getNs()))
            .setGracePeriods(
                ImmutableSet.copyOf(transform(domain.getRgpStatuses(), gracePeriodConverter)))
            .setContacts(ImmutableSet.copyOf(transform(domain.getContacts(), CONTACT_CONVERTER)))
            .setDsData(
                domain.getSecDNS() == null
                    ? ImmutableSet.<DelegationSignerData>of()
                    : ImmutableSet.copyOf(
                        transform(domain.getSecDNS().getDsDatas(), SECDNS_CONVERTER)))
            .setTransferData(convertDomainTransferData(domain.getTrnData()));
    checkArgumentNotNull(
        domain.getRegistrant(), "Registrant is missing for domain '%s'", domain.getName());
    builder = builder.setRegistrant(convertRegistrant(domain.getRegistrant()));
    return builder.build();
  }

  private static BillingEvent.Recurring createAutoRenewBillingEvent(
      XjcRdeDomain domain, HistoryEntry historyEntry) {
    final BillingEvent.Recurring billingEvent =
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(domain.getRoid())
            .setClientId(domain.getClID())
            .setEventTime(domain.getExDate())
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntry)
            .build();
    return billingEvent;
  }

  private static PollMessage.Autorenew createAutoRenewPollMessage(
      XjcRdeDomain domain, HistoryEntry historyEntry) {
    final PollMessage.Autorenew pollMessage =
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getRoid())
            .setClientId(domain.getClID())
            .setEventTime(domain.getExDate())
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntry)
            .build();
    return pollMessage;
  }

  private static HistoryEntry createHistoryEntry(XjcRdeDomain domain) {
    XjcRdeDomainElement element = new XjcRdeDomainElement(domain);
    final HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.RDE_IMPORT)
            .setClientId(domain.getClID())
            .setTrid(Trid.create(null))
            .setModificationTime(DateTime.now())
            .setXmlBytes(getObjectXml(element))
            .setBySuperuser(true)
            .setReason("RDE Import")
            .setRequestedByRegistrar(false)
            .setParent(Key.create(null, DomainResource.class, domain.getRoid()))
            .build();
    return historyEntry;
  }

  /** Returns {@link Key} for registrant from foreign key */
  private static Key<ContactResource> convertRegistrant(String contactId) {
    Key<ContactResource> key =
        ForeignKeyIndex.loadAndGetKey(ContactResource.class, contactId, DateTime.now());
    checkState(key != null, "Registrant not found: '%s'", contactId);
    return key;
  }

  /** Converts {@link XjcDomainNsType} to <code>ImmutableSet<Key<HostResource>></code>. */
  private static ImmutableSet<Key<HostResource>> convertNameservers(XjcDomainNsType ns) {
    // If no hosts are specified, return an empty set
    if (ns == null || (ns.getHostAttrs() == null && ns.getHostObjs() == null)) {
      return ImmutableSet.of();
    }
    // Domain linked hosts must be specified by host object, not host attributes.
    checkArgument(
        ns.getHostAttrs() == null || ns.getHostAttrs().isEmpty(),
        "Host attributes are not yet supported");
    return ImmutableSet.copyOf(Iterables.transform(ns.getHostObjs(), HOST_OBJ_CONVERTER));
  }

  /** Converts {@link XjcRdeDomainTransferDataType} to {@link TransferData}. */
  private static TransferData convertDomainTransferData(XjcRdeDomainTransferDataType data) {
    if (data == null) {
      return TransferData.EMPTY;
    }
    return new TransferData.Builder()
        .setTransferStatus(TRANSFER_STATUS_MAPPER.xmlToEnum(data.getTrStatus().value()))
        .setGainingClientId(data.getReRr().getValue())
        .setLosingClientId(data.getAcRr().getValue())
        .setTransferRequestTime(data.getReDate())
        .setPendingTransferExpirationTime(data.getAcDate())
        .build();
  }

  /** Converts {@link XjcDomainStatusType} to {@link StatusValue}. */
  private static StatusValue convertStatusType(XjcDomainStatusType type) {
    return StatusValue.fromXmlName(type.getS().value());
  }

  /** Converts {@link XjcSecdnsDsDataType} to {@link DelegationSignerData}. */
  private static DelegationSignerData convertSecdnsDsDataType(XjcSecdnsDsDataType secdns) {
    return DelegationSignerData.create(
        secdns.getKeyTag(), secdns.getAlg(), secdns.getDigestType(), secdns.getDigest());
  }

  /** Converts {@link XjcDomainContactType} to {@link DesignatedContact}. */
  private static DesignatedContact convertContactType(XjcDomainContactType contact) {
    String contactId = contact.getValue();
    Key<ContactResource> key =
        ForeignKeyIndex.loadAndGetKey(ContactResource.class, contactId, DateTime.now());
    checkState(key != null, "Contact not found: '%s'", contactId);
    DesignatedContact.Type type =
        DesignatedContact.Type.valueOf(Ascii.toUpperCase(contact.getType().toString()));
    return DesignatedContact.create(type, key);
  }

  private XjcToDomainResourceConverter() {}
}
