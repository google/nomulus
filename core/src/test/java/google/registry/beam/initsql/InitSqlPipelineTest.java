// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.backup.AppEngineEnvironment;
import google.registry.beam.TestPipelineExtension;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link InitSqlPipeline}. */
class InitSqlPipelineTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  /**
   * All kinds of entities to be set up in the Datastore. Must contain all kinds known to {@link
   * InitSqlPipeline}.
   */
  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(
          Registry.class,
          Registrar.class,
          ContactResource.class,
          RegistrarContact.class,
          DomainBase.class,
          HostResource.class,
          HistoryEntry.class,
          AllocationToken.class,
          BillingEvent.Recurring.class,
          BillingEvent.OneTime.class,
          BillingEvent.Cancellation.class,
          PollMessage.class);

  private transient FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @RegisterExtension final transient InjectExtension injectRule = new InjectExtension();

  @SuppressWarnings("WeakerAccess")
  @TempDir
  transient Path tmpDir;

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  private File exportRootDir;
  private File exportDir;
  private File commitLogDir;

  private transient Registrar registrar1;
  private transient Registrar registrar2;
  private transient DomainBase domain;
  private transient ContactResource contact1;
  private transient ContactResource contact2;
  private transient HostResource hostResource;

  private transient DomainHistory historyEntry;

  @BeforeEach
  void beforeEach() throws Exception {
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectRule.setStaticField(Ofy.class, "clock", fakeClock);
      exportRootDir = Files.createDirectory(tmpDir.resolve("exports")).toFile();

      persistResource(newRegistry("com", "COM"));
      registrar1 = persistResource(AppEngineExtension.makeRegistrar1());
      registrar2 = persistResource(AppEngineExtension.makeRegistrar2());
      Key<DomainBase> domainKey = Key.create(null, DomainBase.class, "4-COM");
      hostResource =
          persistResource(
              new HostResource.Builder()
                  .setHostName("ns1.example.com")
                  .setSuperordinateDomain(VKey.from(domainKey))
                  .setRepoId("1-COM")
                  .setCreationRegistrarId(registrar1.getRegistrarId())
                  .setPersistedCurrentSponsorRegistrarId(registrar2.getRegistrarId())
                  .build());
      contact1 =
          persistResource(
              new ContactResource.Builder()
                  .setContactId("contact_id1")
                  .setRepoId("2-COM")
                  .setCreationRegistrarId(registrar1.getRegistrarId())
                  .setPersistedCurrentSponsorRegistrarId(registrar2.getRegistrarId())
                  .build());
      contact2 =
          persistResource(
              new ContactResource.Builder()
                  .setContactId("contact_id2")
                  .setRepoId("3-COM")
                  .setCreationRegistrarId(registrar1.getRegistrarId())
                  .setPersistedCurrentSponsorRegistrarId(registrar1.getRegistrarId())
                  .build());
      persistSimpleResource(
          new RegistrarContact.Builder()
              .setParent(registrar1)
              .setName("John Abused")
              .setEmailAddress("johnabuse@example.com")
              .setVisibleInWhoisAsAdmin(true)
              .setVisibleInWhoisAsTech(false)
              .setPhoneNumber("+1.2125551213")
              .setFaxNumber("+1.2125551213")
              .setTypes(ImmutableSet.of(RegistrarContact.Type.ABUSE, RegistrarContact.Type.ADMIN))
              .build());
      historyEntry =
          persistResource(
              new DomainHistory.Builder()
                  .setDomainRepoId(domainKey.getName())
                  .setModificationTime(fakeClock.nowUtc())
                  .setRegistrarId(registrar1.getRegistrarId())
                  .setType(HistoryEntry.Type.DOMAIN_CREATE)
                  .build());
      persistResource(
          new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
      Key<DomainHistory> historyEntryKey = Key.create(historyEntry);
      BillingEvent.OneTime onetimeBillEvent =
          new BillingEvent.OneTime.Builder()
              .setId(1)
              .setReason(Reason.RENEW)
              .setTargetId("example.com")
              .setRegistrarId("TheRegistrar")
              .setCost(Money.parse("USD 44.00"))
              .setPeriodYears(4)
              .setEventTime(fakeClock.nowUtc())
              .setBillingTime(fakeClock.nowUtc())
              .setParent(historyEntryKey)
              .build();
      persistResource(onetimeBillEvent);
      Key<BillingEvent.OneTime> oneTimeBillKey = Key.create(onetimeBillEvent);
      BillingEvent.Recurring recurringBillEvent =
          new BillingEvent.Recurring.Builder()
              .setId(2)
              .setReason(Reason.RENEW)
              .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
              .setTargetId("example.com")
              .setRegistrarId("TheRegistrar")
              .setEventTime(fakeClock.nowUtc())
              .setRecurrenceEndTime(END_OF_TIME)
              .setParent(historyEntryKey)
              .build();
      persistResource(recurringBillEvent);
      VKey<BillingEvent.Recurring> recurringBillKey = recurringBillEvent.createVKey();
      PollMessage.Autorenew autorenewPollMessage =
          new PollMessage.Autorenew.Builder()
              .setId(3L)
              .setTargetId("example.com")
              .setRegistrarId("TheRegistrar")
              .setEventTime(fakeClock.nowUtc())
              .setMsg("Domain was auto-renewed.")
              .setParent(historyEntry)
              .build();
      persistResource(autorenewPollMessage);
      VKey<PollMessage.Autorenew> autorenewPollKey = autorenewPollMessage.createVKey();
      PollMessage.OneTime oneTimePollMessage =
          new PollMessage.OneTime.Builder()
              .setId(1L)
              .setParent(historyEntry)
              .setEventTime(fakeClock.nowUtc())
              .setRegistrarId("TheRegistrar")
              .setMsg(DomainFlowUtils.COLLISION_MESSAGE)
              .build();
      persistResource(oneTimePollMessage);
      VKey<PollMessage.OneTime> onetimePollKey = oneTimePollMessage.createVKey();
      domain =
          persistResource(
              new DomainBase.Builder()
                  .setDomainName("example.com")
                  .setRepoId("4-COM")
                  .setCreationRegistrarId(registrar1.getRegistrarId())
                  .setLastEppUpdateTime(fakeClock.nowUtc())
                  .setLastEppUpdateRegistrarId(registrar2.getRegistrarId())
                  .setLastTransferTime(fakeClock.nowUtc())
                  .setStatusValues(
                      ImmutableSet.of(
                          StatusValue.CLIENT_DELETE_PROHIBITED,
                          StatusValue.SERVER_DELETE_PROHIBITED,
                          StatusValue.SERVER_TRANSFER_PROHIBITED,
                          StatusValue.SERVER_UPDATE_PROHIBITED,
                          StatusValue.SERVER_RENEW_PROHIBITED,
                          StatusValue.SERVER_HOLD))
                  .setRegistrant(contact1.createVKey())
                  .setContacts(
                      ImmutableSet.of(
                          DesignatedContact.create(
                              DesignatedContact.Type.ADMIN, contact2.createVKey())))
                  .setNameservers(ImmutableSet.of(hostResource.createVKey()))
                  .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
                  .setPersistedCurrentSponsorRegistrarId(registrar2.getRegistrarId())
                  .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
                  .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
                  .setDsData(
                      ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                  .setLaunchNotice(
                      LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
                  .setTransferData(
                      new DomainTransferData.Builder()
                          .setGainingRegistrarId(registrar1.getRegistrarId())
                          .setLosingRegistrarId(registrar2.getRegistrarId())
                          .setPendingTransferExpirationTime(fakeClock.nowUtc())
                          .setServerApproveEntities(
                              ImmutableSet.of(
                                  VKey.from(oneTimeBillKey), recurringBillKey, autorenewPollKey))
                          .setServerApproveBillingEvent(VKey.from(oneTimeBillKey))
                          .setServerApproveAutorenewEvent(recurringBillKey)
                          .setServerApproveAutorenewPollMessage(autorenewPollKey)
                          .setTransferRequestTime(fakeClock.nowUtc().plusDays(1))
                          .setTransferStatus(TransferStatus.SERVER_APPROVED)
                          .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                          .build())
                  .setDeletePollMessage(onetimePollKey)
                  .setAutorenewBillingEvent(recurringBillKey)
                  .setAutorenewPollMessage(autorenewPollKey)
                  .setSmdId("smdid")
                  .addGracePeriod(
                      GracePeriod.create(
                          GracePeriodStatus.ADD,
                          "4-COM",
                          fakeClock.nowUtc().plusDays(1),
                          "TheRegistrar",
                          null))
                  .build());
      persistResource(
          new BillingEvent.Cancellation.Builder()
              .setReason(Reason.RENEW)
              .setTargetId(domain.getDomainName())
              .setRegistrarId(domain.getCurrentSponsorRegistrarId())
              .setEventTime(fakeClock.nowUtc())
              .setBillingTime(fakeClock.nowUtc())
              .setRecurringEventKey(recurringBillEvent.createVKey())
              .setParent(historyEntryKey)
              .build());
      exportDir = store.export(exportRootDir.getAbsolutePath(), ALL_KINDS, ImmutableSet.of());
      commitLogDir = Files.createDirectory(tmpDir.resolve("commits")).toFile();
    }
  }

  @Test
  void runPipeline() {
    InitSqlPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--commitLogStartTimestamp=" + START_TIME,
                "--commitLogEndTimestamp=" + fakeClock.nowUtc().plusMillis(1),
                "--datastoreExportDir=" + exportDir.getAbsolutePath(),
                "--commitLogDir=" + commitLogDir.getAbsolutePath())
            .withValidation()
            .as(InitSqlPipelineOptions.class);
    InitSqlPipeline initSqlPipeline = new InitSqlPipeline(options);
    initSqlPipeline.run(testPipeline).waitUntilFinish();
    try (AppEngineEnvironment env = new AppEngineEnvironment("test")) {
      assertHostResourceEquals(
          jpaTm().transact(() -> jpaTm().loadByKey(hostResource.createVKey())), hostResource);
      assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(Registrar.class)))
          .comparingElementsUsing(immutableObjectCorrespondence("lastUpdateTime"))
          .containsExactly(registrar1, registrar2);
      assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(ContactResource.class)))
          .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
          .containsExactly(contact1, contact2);
      assertDomainEquals(jpaTm().transact(() -> jpaTm().loadByKey(domain.createVKey())), domain);
    }
  }

  private static void assertHostResourceEquals(HostResource actual, HostResource expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(expected, "superordinateDomain", "revisions", "updateTimestamp");
    assertThat(actual.getSuperordinateDomain().getSqlKey())
        .isEqualTo(expected.getSuperordinateDomain().getSqlKey());
  }

  private static void assertDomainEquals(DomainBase actual, DomainBase expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(
            expected,
            "revisions",
            "updateTimestamp",
            "autorenewPollMessage",
            "deletePollMessage",
            "nsHosts",
            "gracePeriods",
            "transferData");
    assertThat(actual.getAdminContact().getSqlKey())
        .isEqualTo(expected.getAdminContact().getSqlKey());
    assertThat(actual.getRegistrant().getSqlKey()).isEqualTo(expected.getRegistrant().getSqlKey());
    assertThat(actual.getNsHosts()).isEqualTo(expected.getNsHosts());
    assertThat(actual.getAutorenewPollMessage().getOfyKey())
        .isEqualTo(expected.getAutorenewPollMessage().getOfyKey());
    assertThat(actual.getDeletePollMessage().getOfyKey())
        .isEqualTo(expected.getDeletePollMessage().getOfyKey());
    // TODO(weiminyu): check gracePeriods and transferData when it is easier to do
  }
}
