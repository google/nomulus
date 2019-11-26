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
import static google.registry.testing.DatastoreHelper.generateNewContactHostRoid;
import static google.registry.testing.DatastoreHelper.generateNewDomainRoid;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResourceWithCommitLog;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.FakeClock;
import google.registry.util.Idn;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Utility class for creating {@code EppResource} entities that'll successfully marshal. */
final class RdeFixtures {

  static DomainBase makeDomainBase(FakeClock clock, String tld) {
    DomainBase domain = new DomainBase.Builder()
        .setFullyQualifiedDomainName("example." + tld)
        .setRepoId(generateNewDomainRoid(tld))
        .setRegistrant(Key.create(
            makeContactResource(clock,
                "5372808-ERL", "(◕‿◕) nevermore", "prophet@evil.みんな")))
        .build();
    HistoryEntry historyEntry =
        persistResource(new HistoryEntry.Builder().setParent(domain).build());
    clock.advanceOneMilli();
    BillingEvent.OneTime billingEvent = persistResourceWithCommitLog(
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.CREATE)
            .setTargetId("example." + tld)
            .setClientId("TheRegistrar")
            .setCost(Money.of(USD, 26))
            .setPeriodYears(2)
            .setEventTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setBillingTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setParent(historyEntry)
            .build());
    domain =
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("secret")))
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(
                        DesignatedContact.Type.ADMIN,
                        Key.create(
                            makeContactResource(
                                clock,
                                "5372808-IRL",
                                "be that word our sign in parting",
                                "BOFH@cat.みんな"))),
                    DesignatedContact.create(
                        DesignatedContact.Type.TECH,
                        Key.create(
                            makeContactResource(
                                clock,
                                "5372808-TRL",
                                "bird or fiend!? i shrieked upstarting",
                                "bog@cat.みんな")))))
            .setCreationClientId("TheRegistrar")
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setCreationTimeForTest(clock.nowUtc())
            .setDsData(
                ImmutableSet.of(
                    DelegationSignerData.create(123, 200, 230, base16().decode("1234567890"))))
            .setFullyQualifiedDomainName(Idn.toASCII("love." + tld))
            .setLastTransferTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("IntoTheTempest")
            .setLastEppUpdateTime(clock.nowUtc())
            .setIdnTableName("extended_latin")
            .setNameservers(
                ImmutableSet.of(
                    Key.create(makeHostResource(clock, "bird.or.devil.みんな", "1.2.3.4")),
                    Key.create(makeHostResource(clock, "ns2.cat.みんな", "bad:f00d:cafe::15:beef"))))
            .setRegistrationExpirationTime(DateTime.parse("1994-01-01T00:00:00Z"))
            .setGracePeriods(
                ImmutableSet.of(
                    GracePeriod.forBillingEvent(
                        GracePeriodStatus.RENEW,
                        persistResource(
                            new BillingEvent.OneTime.Builder()
                                .setReason(Reason.RENEW)
                                .setTargetId("love." + tld)
                                .setClientId("TheRegistrar")
                                .setCost(Money.of(USD, 456))
                                .setPeriodYears(2)
                                .setEventTime(DateTime.parse("1992-01-01T00:00:00Z"))
                                .setBillingTime(DateTime.parse("1992-01-01T00:00:00Z"))
                                .setParent(historyEntry)
                                .build())),
                    GracePeriod.create(
                        GracePeriodStatus.TRANSFER,
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
                Key.create(
                    persistResource(
                        new BillingEvent.Recurring.Builder()
                            .setReason(Reason.RENEW)
                            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                            .setTargetId(tld)
                            .setClientId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setRecurrenceEndTime(END_OF_TIME)
                            .setParent(historyEntry)
                            .build())))
            .setAutorenewPollMessage(
                Key.create(
                    persistSimpleResource(
                        new PollMessage.Autorenew.Builder()
                            .setTargetId(tld)
                            .setClientId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setAutorenewEndTime(END_OF_TIME)
                            .setMsg("Domain was auto-renewed.")
                            .setParent(historyEntry)
                            .build())))
            .setTransferData(
                new TransferData.Builder()
                    .setGainingClientId("gaining")
                    .setLosingClientId("losing")
                    .setPendingTransferExpirationTime(DateTime.parse("1993-04-20T00:00:00Z"))
                    .setServerApproveBillingEvent(Key.create(billingEvent))
                    .setServerApproveAutorenewEvent(
                        Key.create(
                            persistResource(
                                new BillingEvent.Recurring.Builder()
                                    .setReason(Reason.RENEW)
                                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                                    .setTargetId("example." + tld)
                                    .setClientId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setRecurrenceEndTime(END_OF_TIME)
                                    .setParent(historyEntry)
                                    .build())))
                    .setServerApproveAutorenewPollMessage(
                        Key.create(
                            persistResource(
                                new Autorenew.Builder()
                                    .setTargetId("example." + tld)
                                    .setClientId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setAutorenewEndTime(END_OF_TIME)
                                    .setMsg("Domain was auto-renewed.")
                                    .setParent(historyEntry)
                                    .build())))
                    .setServerApproveEntities(ImmutableSet.of(Key.create(billingEvent)))
                    .setTransferRequestTime(DateTime.parse("1991-01-01T00:00:00Z"))
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferredRegistrationExpirationTime(
                        DateTime.parse("2001-01-01T00:00:00.000Z"))
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build();
    clock.advanceOneMilli();
    return persistResourceWithCommitLog(domain);
  }

  static ContactResource makeContactResource(
      FakeClock clock, String id, String name, String email) {
    clock.advanceOneMilli();
    return persistResourceWithCommitLog(
        new ContactResource.Builder()
            .setContactId(id)
            .setRepoId(generateNewContactHostRoid())
            .setEmailAddress(email)
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .setPersistedCurrentSponsorClientId("GetTheeBack")
            .setCreationClientId("GetTheeBack")
            .setCreationTimeForTest(clock.nowUtc())
            .setInternationalizedPostalInfo(new PostalInfo.Builder()
                .setType(PostalInfo.Type.INTERNATIONALIZED)
                .setName(name)
                .setOrg("DOGE INCORPORATED")
                .setAddress(new ContactAddress.Builder()
                    .setStreet(ImmutableList.of("123 Example Boulevard"))
                    .setCity("KOKOMO")
                    .setState("BM")
                    .setZip("31337")
                    .setCountryCode("US")
                    .build())
                .build())
            .setVoiceNumber(
                new ContactPhoneNumber.Builder()
                .setPhoneNumber("+1.5558675309")
                       .build())
            .setFaxNumber(
                new ContactPhoneNumber.Builder()
                .setPhoneNumber("+1.5558675310")
                .build())
            .build());
  }

  static HostResource makeHostResource(FakeClock clock, String fqhn, String ip) {
    clock.advanceOneMilli();
    return persistResourceWithCommitLog(
        new HostResource.Builder()
            .setRepoId(generateNewContactHostRoid())
            .setCreationClientId("LawyerCat")
            .setCreationTimeForTest(clock.nowUtc())
            .setPersistedCurrentSponsorClientId("BusinessCat")
            .setFullyQualifiedHostName(Idn.toASCII(fqhn))
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString(ip)))
            .setLastTransferTime(DateTime.parse("1990-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("CeilingCat")
            .setLastEppUpdateTime(clock.nowUtc())
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .build());
  }

  private RdeFixtures() {}
}
