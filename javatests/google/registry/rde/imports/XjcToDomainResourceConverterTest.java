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

package google.registry.rde.imports;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.imports.RdeImportTestUtils.checkTrid;
import static google.registry.rde.imports.RdeImportUtils.createAutoRenewBillingEventForDomainImport;
import static google.registry.rde.imports.RdeImportUtils.createAutoRenewPollMessageForDomainImport;
import static google.registry.rde.imports.RdeImportUtils.createHistoryEntryForDomainImport;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static java.util.Arrays.asList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
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
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.ExceptionRule;
import google.registry.testing.InjectRule;
import google.registry.util.StringGenerator;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link XjcToDomainResourceConverter} */
@RunWith(JUnit4.class)
public class XjcToDomainResourceConverterTest {

  //List of packages to initialize JAXBContext
  private static final String JAXB_CONTEXT_PACKAGES = Joiner.on(":").join(asList(
      "google.registry.xjc.contact",
      "google.registry.xjc.domain",
      "google.registry.xjc.host",
      "google.registry.xjc.mark",
      "google.registry.xjc.rde",
      "google.registry.xjc.rdecontact",
      "google.registry.xjc.rdedomain",
      "google.registry.xjc.rdeeppparams",
      "google.registry.xjc.rdeheader",
      "google.registry.xjc.rdeidn",
      "google.registry.xjc.rdenndn",
      "google.registry.xjc.rderegistrar",
      "google.registry.xjc.smd"));

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule public final ExceptionRule thrown = new ExceptionRule();

  @Rule public final InjectRule inject = new InjectRule();

  private Unmarshaller unmarshaller;
  private DeterministicStringGenerator stringGenerator;

  @Before
  public void before() throws Exception {
    createTld("example");
    unmarshaller = JAXBContext.newInstance(JAXB_CONTEXT_PACKAGES).createUnmarshaller();
    stringGenerator = new DeterministicStringGenerator(StringGenerator.Alphabets.BASE_64);
    inject.setStaticField(XjcToDomainResourceConverter.class, "stringGenerator", stringGenerator);
  }

  @Test
  public void testConvertDomainResource() throws Exception {
    final ContactResource jd1234 = persistActiveContact("jd1234");
    final ContactResource sh8013 = persistActiveContact("sh8013");
    ImmutableSet<DesignatedContact> expectedContacts =
        ImmutableSet.of(
            DesignatedContact.create(DesignatedContact.Type.ADMIN, Key.create(sh8013)),
            DesignatedContact.create(DesignatedContact.Type.TECH, Key.create(sh8013)));
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getFullyQualifiedDomainName()).isEqualTo("example1.example");
    assertThat(domain.getRepoId()).isEqualTo("Dexample1-TEST");
    // A DomainResource has status INACTIVE if there are no nameservers.
    assertThat(domain.getStatusValues()).isEqualTo(ImmutableSet.of(StatusValue.INACTIVE));
    assertThat(domain.getRegistrant().getName()).isEqualTo(jd1234.getRepoId());
    assertThat(domain.getContacts()).isEqualTo(expectedContacts);
    assertThat(domain.getCurrentSponsorClientId()).isEqualTo("RegistrarX");
    assertThat(domain.getCreationClientId()).isEqualTo("RegistrarX");
    assertThat(domain.getCreationTime()).isEqualTo(DateTime.parse("1999-04-03T22:00:00.0Z"));
    assertThat(domain.getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2015-04-03T22:00:00.0Z"));
    assertThat(domain.getGracePeriods()).isEmpty();
    assertThat(domain.getLastEppUpdateClientId()).isNull();
    assertThat(domain.getLastEppUpdateTime()).isNull();
    assertThat(domain.getAutorenewBillingEvent()).isNotNull();
    assertThat(domain.getAutorenewPollMessage()).isNotNull();
    assertThat(domain.getAuthInfo()).isNotNull();
    assertThat(domain.getAuthInfo().getPw().getValue()).isEqualTo("0123456789abcdef");
  }

  /** Verifies that uppercase domain names are converted to lowercase */
  @Test
  public void testConvertDomainResourceUpperCase() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_ucase.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getFullyQualifiedDomainName()).isEqualTo("example1.example");
  }

  @Test
  public void testConvertDomainResourceAddPeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_addPeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.ADD);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getCrDate().plusDays(5));
  }

  @Test
  public void testConvertDomainResourceAutoRenewPeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_autoRenewPeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.AUTO_RENEW);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getUpDate().plusDays(45));
  }

  @Test
  public void testConvertDomainResourceRedemptionPeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_redemptionPeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.REDEMPTION);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getUpDate().plusDays(30));
  }

  @Test
  public void testConvertDomainResourceRenewPeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_renewPeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.RENEW);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getUpDate().plusDays(5));
  }

  @Test
  public void testConvertDomainResourcePendingDeletePeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pendingDeletePeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.PENDING_DELETE);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getUpDate().plusDays(5));
  }

  @Test
  public void testConvertDomainResourcePendingRestorePeriodUnsupported() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pendingRestorePeriod.xml");
    thrown.expect(
        IllegalArgumentException.class, "Unsupported grace period status: PENDING_RESTORE");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceTransferPeriod() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_transferPeriod.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getGracePeriods()).hasSize(1);
    GracePeriod gracePeriod = domain.getGracePeriods().asList().get(0);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.TRANSFER);
    assertThat(gracePeriod.getClientId()).isEqualTo("RegistrarX");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(xjcDomain.getUpDate().plusDays(5));
    TransferData transferData = domain.getTransferData();
    assertThat(transferData).isNotEqualTo(TransferData.EMPTY);
    assertThat(transferData.getTransferStatus()).isEqualTo(TransferStatus.CLIENT_APPROVED);
    assertThat(transferData.getLosingClientId()).isEqualTo("RegistrarY");
    assertThat(transferData.getTransferRequestTime())
        .isEqualTo(DateTime.parse("2014-10-08T16:23:21.897803Z"));
    assertThat(transferData.getGainingClientId()).isEqualTo("RegistrarX");
    assertThat(transferData.getPendingTransferExpirationTime())
        .isEqualTo(DateTime.parse("2014-10-09T08:25:43.305554Z"));
    assertThat(transferData.getExtendedRegistrationYears()).isEqualTo(1);
  }

  @Test
  public void testConvertDomainResourceEppUpdateRegistrar() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_up_rr.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getLastEppUpdateClientId()).isEqualTo("RegistrarX");
  }

  @Test
  public void testConvertDomainResourceWithHostObjs() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    HostResource host1 = persistActiveHost("ns1.example.net");
    HostResource host2 = persistActiveHost("ns2.example.net");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_host_objs.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    assertThat(domain.getNameservers()).containsExactly(Key.create(host1), Key.create(host2));
  }

  @Test
  public void testConvertDomainResourceWithHostAttrs() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_host_attrs.xml");
    thrown.expect(IllegalArgumentException.class, "Host attributes are not yet supported");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceHostNotFound() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    persistActiveHost("ns1.example.net");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_host_objs.xml");
    thrown.expect(
        IllegalStateException.class, "HostResource not found with name 'ns2.example.net'");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceRegistrantNotFound() throws Exception {
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    thrown.expect(IllegalStateException.class, "Registrant not found: 'jd1234'");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceRegistrantMissing() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_registrant_missing.xml");
    thrown.expect(
        IllegalArgumentException.class, "Registrant is missing for domain 'example1.example'");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceAdminNotFound() throws Exception {
    persistActiveContact("jd1234");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    thrown.expect(IllegalStateException.class, "Contact not found: 'sh8013'");
    convertDomainInTransaction(xjcDomain);
  }

  @Test
  public void testConvertDomainResourceSecDnsData() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_secdns.xml");
    DomainResource domain = convertDomainInTransaction(xjcDomain);
    byte[] digest =
        base16().decode("5FA1FA1C2F70AA483FE178B765D82B272072B4E4167902C5B7F97D46C8899F44");
    assertThat(domain.getDsData()).containsExactly(DelegationSignerData.create(4609, 8, 2, digest));
  }

  @Test
  public void testConvertDomainResourceHistoryEntry() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    // First import in a transaction, then verify in another transaction.
    // Ancestor queries don't work within the same transaction.
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    List<HistoryEntry> historyEntries = getHistoryEntries(domain);
    assertThat(historyEntries).hasSize(1);
    HistoryEntry entry = historyEntries.get(0);
    assertThat(entry.getType()).isEqualTo(HistoryEntry.Type.RDE_IMPORT);
    assertThat(entry.getClientId()).isEqualTo("RegistrarX");
    assertThat(entry.getBySuperuser()).isTrue();
    assertThat(entry.getReason()).isEqualTo("RDE Import");
    assertThat(entry.getRequestedByRegistrar()).isFalse();
    checkTrid(entry.getTrid());
    // check xml against original domain xml
    try (InputStream ins = new ByteArrayInputStream(entry.getXmlBytes())) {
      XjcRdeDomain unmarshalledXml = ((XjcRdeDomainElement) unmarshaller.unmarshal(ins)).getValue();
      assertThat(unmarshalledXml.getName()).isEqualTo(xjcDomain.getName());
      assertThat(unmarshalledXml.getRoid()).isEqualTo(xjcDomain.getRoid());
    }
  }

  @Test
  public void testConvertDomainResourceAutoRenewBillingEvent() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    // First import in a transaction, then verify in another transaction.
    // Ancestor queries don't work within the same transaction.
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    BillingEvent.Recurring autoRenewEvent =
        ofy().load().key(domain.getAutorenewBillingEvent()).now();
    assertThat(autoRenewEvent.getReason()).isEqualTo(Reason.RENEW);
    assertThat(autoRenewEvent.getFlags()).isEqualTo(ImmutableSet.of(Flag.AUTO_RENEW));
    assertThat(autoRenewEvent.getTargetId()).isEqualTo(xjcDomain.getRoid());
    assertThat(autoRenewEvent.getClientId()).isEqualTo("RegistrarX");
    assertThat(autoRenewEvent.getEventTime()).isEqualTo(xjcDomain.getExDate());
    assertThat(autoRenewEvent.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
  }

  @Test
  public void testConvertDomainResourceAutoRenewPollMessage() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment.xml");
    // First import in a transaction, then verify in another transaction.
    // Ancestor queries don't work within the same transaction.
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    PollMessage pollMessage = ofy().load().key(domain.getAutorenewPollMessage()).now();
    assertThat(pollMessage).isInstanceOf(PollMessage.Autorenew.class);
    assertThat(((PollMessage.Autorenew) pollMessage).getTargetId()).isEqualTo(xjcDomain.getRoid());
    assertThat(pollMessage.getClientId()).isEqualTo("RegistrarX");
    assertThat(pollMessage.getEventTime()).isEqualTo(xjcDomain.getExDate());
    assertThat(pollMessage.getMsg()).isEqualTo("Domain was auto-renewed.");
  }

  @Test
  public void testConvertDomainResourcePendingTransfer() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pending_transfer.xml");
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    assertThat(domain.getTransferData()).isNotNull();
    assertThat(domain.getTransferData().getTransferStatus()).isEqualTo(TransferStatus.PENDING);
    assertThat(domain.getTransferData().getGainingClientId()).isEqualTo("RegistrarY");
    assertThat(domain.getTransferData().getTransferRequestTime())
        .isEqualTo(DateTime.parse("2015-01-03T22:00:00.0Z"));
    assertThat(domain.getTransferData().getLosingClientId()).isEqualTo("RegistrarX");
    assertThat(domain.getTransferData().getPendingTransferExpirationTime())
        .isEqualTo(DateTime.parse("2015-01-08T22:00:00.0Z"));
  }

  @Test
  public void testConvertDomainResourcePendingTransferDefaultExtendedYears() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pending_transfer.xml");
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    assertThat(domain.getTransferData()).isNotNull();
    assertThat(domain.getTransferData().getExtendedRegistrationYears()).isEqualTo(1);
  }

  @Test
  public void testConvertDomainResourcePendingTransferExtendOneYear() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pending_transfer_1yr.xml");
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    assertThat(domain.getTransferData()).isNotNull();
    assertThat(domain.getTransferData().getExtendedRegistrationYears()).isEqualTo(1);
  }

  @Test
  public void testConvertDomainResourcePendingTransferExtendTwoYears() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain = loadDomainFromRdeXml("domain_fragment_pending_transfer_2yr.xml");
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    assertThat(domain.getTransferData()).isNotNull();
    assertThat(domain.getTransferData().getExtendedRegistrationYears()).isEqualTo(2);
  }

  @Test
  public void testConvertDomainResourcePendingTransferRegistrationCap() throws Exception {
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    final XjcRdeDomain xjcDomain =
        loadDomainFromRdeXml("domain_fragment_pending_transfer_registration_cap.xml");
    DomainResource domain = persistResource(convertDomainInTransaction(xjcDomain));
    assertThat(domain.getTransferData()).isNotNull();
    // exDate is equal to domain expiration, but extended registration years is always
    // at least 1 year
    assertThat(domain.getTransferData().getExtendedRegistrationYears()).isEqualTo(1);
  }

  private static DomainResource convertDomainInTransaction(final XjcRdeDomain xjcDomain) {
    // HistoryEntry modification times should always match the transaction time of the save
    final HistoryEntry historyEntry = ofy().transact(new Work<HistoryEntry>() {
      @Override
      public HistoryEntry run() {
        return createHistoryEntryForDomainImport(xjcDomain);
      }
    });
    final BillingEvent.Recurring autorenewBillingEvent =
        createAutoRenewBillingEventForDomainImport(xjcDomain, historyEntry);
    final PollMessage.Autorenew autorenewPollMessage =
        createAutoRenewPollMessageForDomainImport(xjcDomain, historyEntry);
    return ofy()
        .transact(
            new Work<DomainResource>() {
              @SuppressWarnings("unchecked")
              @Override
              public DomainResource run() {
                ofy().save().entities(historyEntry, autorenewBillingEvent, autorenewPollMessage);
                return XjcToDomainResourceConverter.convertDomain(
                    xjcDomain, autorenewBillingEvent, autorenewPollMessage);
              }
            });
  }

  private XjcRdeDomain loadDomainFromRdeXml(String filename) {
    try {
      ByteSource source = RdeImportsTestData.get(filename);
      try (InputStream ins = source.openStream()) {
        return ((XjcRdeDomainElement) unmarshaller.unmarshal(ins)).getValue();
      }
    } catch (JAXBException | IOException e) {
      throw new RuntimeException(e);
    }
  }
}
