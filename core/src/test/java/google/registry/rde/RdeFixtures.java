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

import static com.google.common.io.BaseEncoding.base16;
import static google.registry.testing.DatabaseHelper.generateNewContactHostRoid;
import static google.registry.testing.DatabaseHelper.generateNewDomainRoid;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.FakeClock;
import google.registry.util.Idn;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Utility class for creating {@code EppResource} entities that'll successfully marshal. */
final class RdeFixtures {

  static Domain makeDomain(FakeClock clock, String tld) {
    Domain domain =
        new Domain.Builder()
            .setDomainName("example." + tld)
            .setRepoId(generateNewDomainRoid(tld))
            .setRegistrant(
                Optional.of(
                    makeContact(clock, "5372808-ERL", "(◕‿◕) nevermore", "prophet@evil.みんな")
                        .createVKey()))
            .build();
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(clock.nowUtc())
                .setRegistrarId("TheRegistrar")
                .build());
    clock.advanceOneMilli();
    BillingEvent billingEvent =
        persistResource(
            new BillingEvent.Builder()
                .setReason(Reason.CREATE)
                .setTargetId("example." + tld)
                .setRegistrarId("TheRegistrar")
                .setCost(Money.of(USD, 26))
                .setPeriodYears(2)
                .setEventTime(DateTime.parse("1990-01-01T00:00:00Z"))
                .setBillingTime(DateTime.parse("1990-01-01T00:00:00Z"))
                .setDomainHistory(historyEntry)
                .build());
    domain =
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("secret")))
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(
                        DesignatedContact.Type.ADMIN,
                        makeContact(
                                clock,
                                "5372808-IRL",
                                "be that word our sign in parting",
                                "BOFH@cat.みんな")
                            .createVKey()),
                    DesignatedContact.create(
                        DesignatedContact.Type.TECH,
                        makeContact(
                                clock,
                                "5372808-TRL",
                                "bird or fiend!? i shrieked upstarting",
                                "bog@cat.みんな")
                            .createVKey())))
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setCreationTimeForTest(clock.nowUtc())
            .setDsData(
                ImmutableSet.of(DomainDsData.create(123, 200, 230, base16().decode("1234567890"))))
            .setDomainName(Idn.toASCII("love." + tld))
            .setLastTransferTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setLastEppUpdateRegistrarId("IntoTheTempest")
            .setLastEppUpdateTime(clock.nowUtc())
            .setIdnTableName("extended_latin")
            .setNameservers(
                ImmutableSet.of(
                    makeHost(clock, "bird.or.devil.みんな", "1.2.3.4").createVKey(),
                    makeHost(clock, "ns2.cat.みんな", "bad:f00d:cafe::15:beef").createVKey()))
            .setRegistrationExpirationTime(DateTime.parse("1994-01-01T00:00:00Z"))
            .setGracePeriods(
                ImmutableSet.of(
                    GracePeriod.forBillingEvent(
                        GracePeriodStatus.RENEW,
                        domain.getRepoId(),
                        persistResource(
                            new BillingEvent.Builder()
                                .setReason(Reason.RENEW)
                                .setTargetId("love." + tld)
                                .setRegistrarId("TheRegistrar")
                                .setCost(Money.of(USD, 456))
                                .setPeriodYears(2)
                                .setEventTime(DateTime.parse("1992-01-01T00:00:00Z"))
                                .setBillingTime(DateTime.parse("1992-01-01T00:00:00Z"))
                                .setDomainHistory(historyEntry)
                                .build())),
                    GracePeriod.create(
                        GracePeriodStatus.TRANSFER,
                        domain.getRepoId(),
                        DateTime.parse("1992-01-01T00:00:00Z"),
                        "foo",
                        null)))
            .setSubordinateHosts(ImmutableSet.of("home.by.horror.haunted"))
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.CLIENT_RENEW_PROHIBITED,
                    StatusValue.CLIENT_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED))
            .setAutorenewBillingEvent(
                persistResource(
                        new BillingRecurrence.Builder()
                            .setReason(Reason.RENEW)
                            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                            .setTargetId(tld)
                            .setRegistrarId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setRecurrenceEndTime(END_OF_TIME)
                            .setDomainHistory(historyEntry)
                            .build())
                    .createVKey())
            .setAutorenewPollMessage(
                persistResource(
                        new PollMessage.Autorenew.Builder()
                            .setTargetId(tld)
                            .setRegistrarId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setAutorenewEndTime(END_OF_TIME)
                            .setMsg("Domain was auto-renewed.")
                            .setHistoryEntry(historyEntry)
                            .build())
                    .createVKey())
            .setTransferData(
                new DomainTransferData.Builder()
                    .setGainingRegistrarId("gaining")
                    .setLosingRegistrarId("losing")
                    .setPendingTransferExpirationTime(DateTime.parse("1993-04-20T00:00:00Z"))
                    .setServerApproveBillingEvent(billingEvent.createVKey())
                    .setServerApproveAutorenewEvent(
                        persistResource(
                                new BillingRecurrence.Builder()
                                    .setReason(Reason.RENEW)
                                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                                    .setTargetId("example." + tld)
                                    .setRegistrarId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setRecurrenceEndTime(END_OF_TIME)
                                    .setDomainHistory(historyEntry)
                                    .build())
                            .createVKey())
                    .setServerApproveAutorenewPollMessage(
                        persistResource(
                                new Autorenew.Builder()
                                    .setTargetId("example." + tld)
                                    .setRegistrarId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setAutorenewEndTime(END_OF_TIME)
                                    .setMsg("Domain was auto-renewed.")
                                    .setHistoryEntry(historyEntry)
                                    .build())
                            .createVKey())
                    .setServerApproveEntities(
                        historyEntry.getRepoId(),
                        historyEntry.getRevisionId(),
                        ImmutableSet.of(billingEvent.createVKey()))
                    .setTransferRequestTime(DateTime.parse("1991-01-01T00:00:00Z"))
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferredRegistrationExpirationTime(
                        DateTime.parse("1995-01-01T00:00:00.000Z"))
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build();
    clock.advanceOneMilli();
    return persistResource(domain);
  }

  static Contact makeContact(FakeClock clock, String id, String name, String email) {
    clock.advanceOneMilli();
    return persistResource(
        new Contact.Builder()
            .setContactId(id)
            .setRepoId(generateNewContactHostRoid())
            .setEmailAddress(email)
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .setPersistedCurrentSponsorRegistrarId("GetTheeBack")
            .setCreationRegistrarId("GetTheeBack")
            .setCreationTimeForTest(clock.nowUtc())
            .setInternationalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(PostalInfo.Type.INTERNATIONALIZED)
                    .setName(name)
                    .setOrg("DOGE INCORPORATED")
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 Example Boulevard"))
                            .setCity("KOKOMO")
                            .setState("BM")
                            .setZip("31337")
                            .setCountryCode("US")
                            .build())
                    .build())
            .setVoiceNumber(
                new ContactPhoneNumber.Builder().setPhoneNumber("+1.5558675309").build())
            .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("+1.5558675310").build())
            .build());
  }

  static Host makeHost(FakeClock clock, String fqhn, String ip) {
    clock.advanceOneMilli();
    return persistResource(
        new Host.Builder()
            .setRepoId(generateNewContactHostRoid())
            .setCreationRegistrarId("LawyerCat")
            .setCreationTimeForTest(clock.nowUtc())
            .setPersistedCurrentSponsorRegistrarId("BusinessCat")
            .setHostName(Idn.toASCII(fqhn))
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString(ip)))
            .setLastTransferTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setLastEppUpdateRegistrarId("CeilingCat")
            .setLastEppUpdateTime(clock.nowUtc())
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .build());
  }

  private RdeFixtures() {}
}
