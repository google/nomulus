// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.BulkQueryJpaFactory;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;

/**
 * Utilities for managing domain-related SQL test scenarios.
 *
 * <p>The {@link #initializeAllEntities} method initializes the database, and the {@link
 * #applyChangeToDomainAndHistory} makes one change to the domain, generating an additional history
 * event. The most up-to-date values of the relevant entities are saved in public instance
 * variables, {@link #domain} etc.
 *
 * <p>This class makes use of {@link DatabaseHelper}, which requires Datastore. Tests that use this
 * class should use {@link AppEngineExtension}.
 */
public final class TestSetupHelper {

  public static final String TLD = "tld";
  public static final String DOMAIN_REPO_ID = "4-TLD";
  public static final String DOMAIN_NAME = "example.tld";
  public static final String REGISTRAR_ID = "AnRegistrar";

  private final FakeClock fakeClock;

  public Registry registry;
  public Registrar registrar;
  public ContactResource contact;
  public DomainBase domain;
  public DomainHistory domainHistory;
  public HostResource host;

  private JpaTransactionManager originalJpaTm;
  private JpaTransactionManager bulkQueryJpaTm;

  public TestSetupHelper(FakeClock fakeClock) {
    this.fakeClock = fakeClock;
  }

  public void initializeAllEntities() {
    registry = putInDb(DatabaseHelper.newRegistry(TLD, Ascii.toUpperCase(TLD)));
    registrar = saveRegistrar(REGISTRAR_ID);
    contact = putInDb(createContact(DOMAIN_REPO_ID, REGISTRAR_ID));
    domain = putInDb(createSimpleDomain(contact));
    domainHistory = putInDb(createHistoryWithoutContent(domain, fakeClock));
    host = putInDb(createHost());
  }

  public void applyChangeToDomainAndHistory() {
    domain = putInDb(createFullDomain(contact, host, fakeClock));
    domainHistory = putInDb(createFullHistory(domain, fakeClock));
  }

  public void setupBulkQueryJpaTm(AppEngineExtension appEngineExtension) {
    bulkQueryJpaTm =
        BulkQueryJpaFactory.createBulkQueryJpaTransactionManager(
            appEngineExtension
                .getJpaIntegrationTestExtension()
                .map(JpaIntegrationTestExtension::getJpaProperties)
                .orElseThrow(
                    () -> new IllegalStateException("Expecting JpaIntegrationTestExtension.")),
            fakeClock);
    originalJpaTm = TransactionManagerFactory.jpaTm();
    TransactionManagerFactory.setJpaTm(() -> bulkQueryJpaTm);
  }

  public void tearDownBulkQueryJpaTm() {
    if (bulkQueryJpaTm != null) {
      bulkQueryJpaTm.teardown();
      TransactionManagerFactory.setJpaTm(() -> originalJpaTm);
    }
  }

  static ContactResource createContact(String repoId, String registrarId) {
    return new ContactResource.Builder()
        .setRepoId(repoId)
        .setCreationRegistrarId(registrarId)
        .setTransferData(new ContactTransferData.Builder().build())
        .setPersistedCurrentSponsorRegistrarId(registrarId)
        .build();
  }

  static DomainBase createSimpleDomain(ContactResource contact) {
    return DatabaseHelper.newDomainBase(DOMAIN_NAME, DOMAIN_REPO_ID, contact)
        .asBuilder()
        .setCreationRegistrarId(REGISTRAR_ID)
        .setPersistedCurrentSponsorRegistrarId(REGISTRAR_ID)
        .build();
  }

  static DomainBase createFullDomain(
      ContactResource contact, HostResource host, FakeClock fakeClock) {
    return createSimpleDomain(contact)
        .asBuilder()
        .setDomainName(DOMAIN_NAME)
        .setRepoId(DOMAIN_REPO_ID)
        .setCreationRegistrarId(REGISTRAR_ID)
        .setLastEppUpdateTime(fakeClock.nowUtc())
        .setLastEppUpdateRegistrarId(REGISTRAR_ID)
        .setLastTransferTime(fakeClock.nowUtc())
        .setNameservers(host.createVKey())
        .setStatusValues(
            ImmutableSet.of(
                StatusValue.CLIENT_DELETE_PROHIBITED,
                StatusValue.SERVER_DELETE_PROHIBITED,
                StatusValue.SERVER_TRANSFER_PROHIBITED,
                StatusValue.SERVER_UPDATE_PROHIBITED,
                StatusValue.SERVER_RENEW_PROHIBITED,
                StatusValue.SERVER_HOLD))
        .setContacts(
            ImmutableSet.of(
                DesignatedContact.create(DesignatedContact.Type.ADMIN, contact.createVKey())))
        .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
        .setPersistedCurrentSponsorRegistrarId(REGISTRAR_ID)
        .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
        .setLaunchNotice(LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
        .setSmdId("smdid")
        .addGracePeriod(
            GracePeriod.create(
                GracePeriodStatus.ADD, DOMAIN_REPO_ID, END_OF_TIME, REGISTRAR_ID, null, 100L))
        .build();
  }

  static HostResource createHost() {
    return new HostResource.Builder()
        .setRepoId("host1")
        .setHostName("ns1.example.com")
        .setCreationRegistrarId(REGISTRAR_ID)
        .setPersistedCurrentSponsorRegistrarId(REGISTRAR_ID)
        .build();
  }

  static DomainTransactionRecord createDomainTransactionRecord(FakeClock fakeClock) {
    return new DomainTransactionRecord.Builder()
        .setTld(TLD)
        .setReportingTime(fakeClock.nowUtc())
        .setReportField(TransactionReportField.NET_ADDS_1_YR)
        .setReportAmount(1)
        .build();
  }

  static DomainHistory createHistoryWithoutContent(DomainBase domain, FakeClock fakeClock) {
    return new DomainHistory.Builder()
        .setType(HistoryEntry.Type.DOMAIN_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setRegistrarId(REGISTRAR_ID)
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setDomainRepoId(domain.getRepoId())
        .setOtherRegistrarId("otherClient")
        .setPeriod(Period.create(1, Period.Unit.YEARS))
        .build();
  }

  static DomainHistory createFullHistory(DomainBase domain, FakeClock fakeClock) {
    return createHistoryWithoutContent(domain, fakeClock)
        .asBuilder()
        .setType(HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE)
        .setDomain(domain)
        .setDomainTransactionRecords(ImmutableSet.of(createDomainTransactionRecord(fakeClock)))
        .build();
  }

  static <T> T putInDb(T entity) {
    jpaTm().transact(() -> jpaTm().put(entity));
    return jpaTm().transact(() -> jpaTm().loadByEntity(entity));
  }
}
