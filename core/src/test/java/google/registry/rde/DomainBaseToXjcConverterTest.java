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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.xjc.rgp.XjcRgpStatusValueType.RENEW_PERIOD;
import static google.registry.xjc.rgp.XjcRgpStatusValueType.TRANSFER_PERIOD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
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
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.rde.RdeMode;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestOfyAndSql;
import google.registry.util.Idn;
import google.registry.xjc.domain.XjcDomainStatusType;
import google.registry.xjc.domain.XjcDomainStatusValueType;
import google.registry.xjc.rde.XjcRdeContentsType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rde.XjcRdeMenuType;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import google.registry.xjc.rgp.XjcRgpStatusType;
import google.registry.xjc.secdns.XjcSecdnsDsDataType;
import java.io.ByteArrayOutputStream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link DomainBaseToXjcConverter}.
 *
 * <p>This tests the mapping between {@link DomainBase} and {@link XjcRdeDomain} as well as some
 * exceptional conditions.
 */
@DualDatabaseTest
public class DomainBaseToXjcConverterTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final DateTime now = DateTime.parse("2014-01-01T00:00:00Z");
  private final FakeClock clock = new FakeClock(now);

  @BeforeEach
  void beforeEach() {
    createTld("xn--q9jyb4c");
  }

  @TestOfyAndSql
  void testConvertThick() {
    XjcRdeDomain bean = DomainBaseToXjcConverter.convertDomain(makeDomainBase(clock), RdeMode.FULL);

    assertThat(bean.getClID()).isEqualTo("TheRegistrar");

    assertThat(
            bean.getContacts().stream()
                .map(input -> String.format("%s %s", input.getType().toString(), input.getValue())))
        .containsExactly("ADMIN 5372808-IRL", "TECH 5372808-TRL");

    assertThat(bean.getCrDate()).isEqualTo(DateTime.parse("1900-01-01T00:00:00Z"));

    // o  A <crRr> element that contains the identifier of the registrar
    //    that created the domain name object.  An OPTIONAL client attribute
    //    is used to specify the client that performed the operation.
    //    This will always be null for us since we track each registrar as a separate client.
    assertThat(bean.getCrRr().getValue()).isEqualTo("TheRegistrar");
    assertThat(bean.getCrRr().getClient()).isNull();
    assertThat(bean.getExDate()).isEqualTo(DateTime.parse("1930-01-01T00:00:00Z"));

    // o  An OPTIONAL <idnTableId> element that references the IDN Table
    //    used for the IDN.  This corresponds to the "id" attribute of the
    //    <idnTableRef> element.  This element MUST be present if the domain
    //    name is an IDN.
    // TODO(b/26125498): bean.getIdnTableId()

    assertThat(bean.getName()).isEqualTo("love.xn--q9jyb4c");
    assertThat(bean.getUName()).isEqualTo("love.みんな");

    assertThat(bean.getNs().getHostObjs())
        .containsExactly("bird.or.devil.xn--q9jyb4c", "ns2.cat.xn--q9jyb4c");

    // o  An OPTIONAL <originalName> element is used to indicate that the
    //    domain name is an IDN variant.  This element contains the domain
    //    name used to generate the IDN variant.
    // TODO(b/26125498): bean.getOriginalName()

    assertThat(bean.getRegistrant()).isEqualTo("5372808-ERL");

    // o  Zero or more OPTIONAL <rgpStatus> element to represent
    //    "pendingDelete" sub-statuses, including "redemptionPeriod",
    //    "pendingRestore", and "pendingDelete", that a domain name can be
    //    in as a result of grace period processing as specified in [RFC3915].
    assertThat(bean.getRgpStatuses().stream().map(XjcRgpStatusType::getS))
        .containsExactly(TRANSFER_PERIOD, RENEW_PERIOD);

    assertWithMessage("secdns").that(bean.getSecDNS()).isNotNull();
    assertWithMessage("secdns dsdata").that(bean.getSecDNS().getDsDatas()).isNotNull();
    assertWithMessage("secdns dsdata").that(bean.getSecDNS().getDsDatas()).hasSize(1);
    XjcSecdnsDsDataType dsData = bean.getSecDNS().getDsDatas().get(0);
    assertThat(dsData.getAlg()).isEqualTo((short) 200);
    assertThat(dsData.getDigest()).isEqualTo(base16().decode("1234567890"));
    assertThat(dsData.getDigestType()).isEqualTo((short) 230);
    assertThat(dsData.getKeyTag()).isEqualTo(123);
    // TODO(b/26125499): Test dsData.getKeyData()

    assertThat(bean.getRoid()).isEqualTo("2-Q9JYB4C");

    assertThat(bean.getStatuses().stream().map(XjcDomainStatusType::getS))
        .containsExactly(
            XjcDomainStatusValueType.CLIENT_DELETE_PROHIBITED,
            XjcDomainStatusValueType.CLIENT_RENEW_PROHIBITED,
            XjcDomainStatusValueType.CLIENT_TRANSFER_PROHIBITED,
            XjcDomainStatusValueType.SERVER_UPDATE_PROHIBITED);

    assertThat(bean.getTrDate()).isEqualTo(DateTime.parse("1910-01-01T00:00:00Z"));

    assertThat(bean.getTrnData().getTrStatus().toString()).isEqualTo("PENDING");
    assertThat(bean.getTrnData().getReRr().getValue()).isEqualTo("NewRegistrar");
    assertThat(bean.getTrnData().getAcRr().getValue()).isEqualTo("TheRegistrar");
    assertThat(bean.getTrnData().getAcDate()).isEqualTo(DateTime.parse("1925-04-20T00:00:00Z"));
    assertThat(bean.getTrnData().getReDate()).isEqualTo(DateTime.parse("1919-01-01T00:00:00Z"));
    assertThat(bean.getTrnData().getExDate()).isEqualTo(DateTime.parse("1931-01-01T00:00:00Z"));

    assertThat(bean.getUpDate()).isEqualTo(DateTime.parse("1920-01-01T00:00:00Z"));

    assertThat(bean.getUpRr().getValue()).isEqualTo("TheRegistrar");
    assertThat(bean.getUpRr().getClient()).isNull();
  }

  @TestOfyAndSql
  void testConvertThin() {
    XjcRdeDomain bean = DomainBaseToXjcConverter.convertDomain(makeDomainBase(clock), RdeMode.THIN);
    assertThat(bean.getRegistrant()).isNull();
    assertThat(bean.getContacts()).isEmpty();
    assertThat(bean.getSecDNS()).isNull();
  }

  @TestOfyAndSql
  void testMarshalThick() throws Exception {
    XjcRdeDomain bean = DomainBaseToXjcConverter.convertDomain(makeDomainBase(clock), RdeMode.FULL);
    wrapDeposit(bean).marshal(new ByteArrayOutputStream(), UTF_8);
  }

  @TestOfyAndSql
  void testMarshalThin() throws Exception {
    XjcRdeDomain bean = DomainBaseToXjcConverter.convertDomain(makeDomainBase(clock), RdeMode.THIN);
    wrapDeposit(bean).marshal(new ByteArrayOutputStream(), UTF_8);
  }

  XjcRdeDeposit wrapDeposit(XjcRdeDomain domain) {
    XjcRdeDeposit deposit = new XjcRdeDeposit();
    deposit.setId("984302");
    deposit.setType(XjcRdeDepositTypeType.FULL);
    deposit.setWatermark(new DateTime("2012-01-01T04:20:00Z"));
    XjcRdeMenuType menu = new XjcRdeMenuType();
    menu.setVersion("1.0");
    menu.getObjURIs().add("lol");
    deposit.setRdeMenu(menu);
    XjcRdeDomainElement element = new XjcRdeDomainElement();
    element.setValue(domain);
    XjcRdeContentsType contents = new XjcRdeContentsType();
    contents.getContents().add(element);
    deposit.setContents(contents);
    return deposit;
  }

  static DomainBase makeDomainBase(FakeClock clock) {
    DomainBase domain =
        persistResource(
            newDomainBase("example.xn--q9jyb4c").asBuilder().setRepoId("2-Q9JYB4C").build());
    DomainHistory domainHistory =
        persistResource(
            new DomainHistory.Builder()
                .setModificationTime(clock.nowUtc())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setDomainContent(domain)
                .build());
    BillingEvent.OneTime billingEvent =
        persistResource(
            new BillingEvent.OneTime.Builder()
                .setReason(Reason.CREATE)
                .setTargetId("example.xn--q9jyb4c")
                .setClientId("TheRegistrar")
                .setCost(Money.of(USD, 26))
                .setPeriodYears(2)
                .setEventTime(DateTime.parse("1910-01-01T00:00:00Z"))
                .setBillingTime(DateTime.parse("1910-01-01T00:00:00Z"))
                .setParent(domainHistory)
                .build());
    domain =
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("secret")))
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(
                        DesignatedContact.Type.ADMIN,
                        makeContactResource(
                                clock,
                                "10-Q9JYB4C",
                                "5372808-IRL",
                                "be that word our sign in parting",
                                "BOFH@cat.みんな")
                            .createVKey()),
                    DesignatedContact.create(
                        DesignatedContact.Type.TECH,
                        makeContactResource(
                                clock,
                                "11-Q9JYB4C",
                                "5372808-TRL",
                                "bird or fiend!? i shrieked upstarting",
                                "bog@cat.みんな")
                            .createVKey())))
            .setCreationClientId("TheRegistrar")
            .setCreationTimeForTest(DateTime.parse("1900-01-01T00:00:00Z"))
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setDsData(
                ImmutableSet.of(
                    DelegationSignerData.create(123, 200, 230, base16().decode("1234567890"))))
            .setDomainName(Idn.toASCII("love.みんな"))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("TheRegistrar")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setNameservers(
                ImmutableSet.of(
                    makeHostResource(clock, "3-Q9JYB4C", "bird.or.devil.みんな", "1.2.3.4")
                        .createVKey(),
                    makeHostResource(clock, "4-Q9JYB4C", "ns2.cat.みんな", "bad:f00d:cafe::15:beef")
                        .createVKey()))
            .setRegistrant(
                makeContactResource(
                        clock, "12-Q9JYB4C", "5372808-ERL", "(◕‿◕) nevermore", "prophet@evil.みんな")
                    .createVKey())
            .setRegistrationExpirationTime(DateTime.parse("1930-01-01T00:00:00Z"))
            .setGracePeriods(
                ImmutableSet.of(
                    GracePeriod.forBillingEvent(
                        GracePeriodStatus.RENEW,
                        domain.getRepoId(),
                        persistResource(
                            new BillingEvent.OneTime.Builder()
                                .setReason(Reason.RENEW)
                                .setTargetId("love.xn--q9jyb4c")
                                .setClientId("TheRegistrar")
                                .setCost(Money.of(USD, 456))
                                .setPeriodYears(2)
                                .setEventTime(DateTime.parse("1920-01-01T00:00:00Z"))
                                .setBillingTime(DateTime.parse("1920-01-01T00:00:00Z"))
                                .setParent(domainHistory)
                                .build())),
                    GracePeriod.create(
                        GracePeriodStatus.TRANSFER,
                        domain.getRepoId(),
                        DateTime.parse("1920-01-01T00:00:00Z"),
                        "TheRegistrar",
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
                        new BillingEvent.Recurring.Builder()
                            .setReason(Reason.RENEW)
                            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                            .setTargetId("lol")
                            .setClientId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setRecurrenceEndTime(END_OF_TIME)
                            .setParent(domainHistory)
                            .build())
                    .createVKey())
            .setAutorenewPollMessage(
                persistResource(
                        new PollMessage.Autorenew.Builder()
                            .setTargetId("lol")
                            .setClientId("TheRegistrar")
                            .setEventTime(END_OF_TIME)
                            .setAutorenewEndTime(END_OF_TIME)
                            .setMsg("Domain was auto-renewed.")
                            .setParent(domainHistory)
                            .build())
                    .createVKey())
            .setTransferData(
                new DomainTransferData.Builder()
                    .setGainingClientId("NewRegistrar")
                    .setLosingClientId("TheRegistrar")
                    .setPendingTransferExpirationTime(DateTime.parse("1925-04-20T00:00:00Z"))
                    .setServerApproveBillingEvent(billingEvent.createVKey())
                    .setServerApproveAutorenewEvent(
                        persistResource(
                                new BillingEvent.Recurring.Builder()
                                    .setReason(Reason.RENEW)
                                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                                    .setTargetId("example.xn--q9jyb4c")
                                    .setClientId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setRecurrenceEndTime(END_OF_TIME)
                                    .setParent(domainHistory)
                                    .build())
                            .createVKey())
                    .setServerApproveAutorenewPollMessage(
                        persistResource(
                                new Autorenew.Builder()
                                    .setTargetId("example.xn--q9jyb4c")
                                    .setClientId("TheRegistrar")
                                    .setEventTime(END_OF_TIME)
                                    .setAutorenewEndTime(END_OF_TIME)
                                    .setMsg("Domain was auto-renewed.")
                                    .setParent(domainHistory)
                                    .build())
                            .createVKey())
                    .setServerApproveEntities(ImmutableSet.of(billingEvent.createVKey()))
                    .setTransferRequestTime(DateTime.parse("1919-01-01T00:00:00Z"))
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferredRegistrationExpirationTime(
                        DateTime.parse("1931-01-01T00:00:00Z"))
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build();
    clock.advanceOneMilli();
    return persistResource(domain);
  }

  private static ContactResource makeContactResource(
      FakeClock clock, String repoId, String id, String name, String email) {
    clock.advanceOneMilli();
    return persistEppResource(
        new ContactResource.Builder()
            .setContactId(id)
            .setEmailAddress(email)
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setCreationClientId("TheRegistrar")
            .setCreationTimeForTest(END_OF_TIME)
            .setInternationalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(PostalInfo.Type.INTERNATIONALIZED)
                    .setName(name)
                    .setOrg("SINNERS INCORPORATED")
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 Example Boulevard"))
                            .setCity("KOKOMO")
                            .setState("BM")
                            .setZip("31337")
                            .setCountryCode("US")
                            .build())
                    .build())
            .setRepoId(repoId)
            .setVoiceNumber(
                new ContactPhoneNumber.Builder().setPhoneNumber("+1.2126660420").build())
            .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("+1.2126660421").build())
            .build());
  }

  private static HostResource makeHostResource(
      FakeClock clock, String repoId, String fqhn, String ip) {
    clock.advanceOneMilli();
    return persistEppResource(
        new HostResource.Builder()
            .setCreationClientId("TheRegistrar")
            .setCreationTimeForTest(DateTime.parse("1900-01-01T00:00:00Z"))
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setHostName(Idn.toASCII(fqhn))
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString(ip)))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("TheRegistrar")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setRepoId(repoId)
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .build());
  }
}
