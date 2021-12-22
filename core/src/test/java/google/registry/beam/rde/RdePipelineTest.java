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

package google.registry.beam.rde;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.beam.rde.RdePipeline.decodePendingDeposits;
import static google.registry.beam.rde.RdePipeline.encodePendingDeposits;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeResourceType.CONTACT;
import static google.registry.rde.RdeResourceType.DOMAIN;
import static google.registry.rde.RdeResourceType.HOST;
import static google.registry.rde.RdeResourceType.REGISTRAR;
import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.AppEngineExtension.makeRegistrar2;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.insertSimpleResources;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.io.BaseEncoding;
import google.registry.beam.TestPipelineExtension;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.PgpHelper;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeRevision;
import google.registry.model.rde.RdeRevision.RdeRevisionId;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.rde.DepositFragment;
import google.registry.rde.Ghostryde;
import google.registry.rde.PendingDeposit;
import google.registry.rde.RdeResourceType;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.TmOverrideExtension;
import java.io.IOException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;

/** Unit tests for {@link RdePipeline}. */
public class RdePipelineTest {

  private static final String REGISTRAR_NAME_PATTERN =
      "<rdeRegistrar:name>(.*)</rdeRegistrar:name>";

  private static final String DOMAIN_NAME_PATTERN = "<rdeDomain:name>(.*)</rdeDomain:name>";

  private static final String CONTACT_ID_PATTERN = "<rdeContact:id>(.*)</rdeContact:id>";

  private static final String HOST_NAME_PATTERN = "<rdeHost:name>(.*)</rdeHost:name>";

  // This is the default creation time for test data.
  private final FakeClock clock = new FakeClock(DateTime.parse("1999-12-31TZ"));

  // This is teh default as-of time the RDE/BRDA job.
  private final DateTime now = DateTime.parse("2000-01-01TZ");

  private final ImmutableSet<PendingDeposit> pendings =
      ImmutableSet.of(
          PendingDeposit.create("soy", now, FULL, RDE_STAGING, Duration.standardDays(1)),
          PendingDeposit.create("soy", now, THIN, RDE_STAGING, Duration.standardDays(1)),
          PendingDeposit.create("fun", now, FULL, RDE_STAGING, Duration.standardDays(1)));

  private final ImmutableList<DepositFragment> brdaFragments =
      ImmutableList.of(
          DepositFragment.create(RdeResourceType.DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(RdeResourceType.REGISTRAR, "<rdeRegistrar:registrar/>\n", ""));

  private final ImmutableList<DepositFragment> rdeFragments =
      ImmutableList.of(
          DepositFragment.create(RdeResourceType.DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(RdeResourceType.REGISTRAR, "<rdeRegistrar:registrar/>\n", ""),
          DepositFragment.create(CONTACT, "<rdeContact:contact/>\n", ""),
          DepositFragment.create(HOST, "<rdeHost:host/>\n", ""));

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  private final PGPPublicKey encryptionKey =
      new FakeKeyringModule().get().getRdeStagingEncryptionKey();

  private final PGPPrivateKey decryptionKey =
      new FakeKeyringModule().get().getRdeStagingDecryptionKey();

  private final RdePipelineOptions options =
      PipelineOptionsFactory.create().as(RdePipelineOptions.class);

  // The pipeline runs in a different thread, which needs to be masqueraded as a GAE thread as well.
  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final DatastoreEntityExtension datastore = new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension
  @Order(Order.DEFAULT + 1)
  TmOverrideExtension tmOverrideExtension = TmOverrideExtension.withJpa();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.fromOptions(options).enableAbandonedNodeEnforcement(true);

  private RdePipeline rdePipeline;

  private ContactHistory persistContactHistory(ContactBase contact) {
    return persistResource(
        new ContactHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(clock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setContact(contact)
            .setContactRepoId(contact.getRepoId())
            .build());
  }

  private DomainHistory persistDomainHistory(DomainContent domain) {
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("soy")
            .setReportingTime(clock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();

    return persistResource(
        new DomainHistory.Builder()
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(clock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setDomain(domain)
            .setDomainRepoId(domain.getRepoId())
            .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
            .setOtherRegistrarId("otherClient")
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .build());
  }

  private HostHistory persistHostHistory(HostBase hostBase) {
    return persistResource(
        new HostHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(clock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setHost(hostBase)
            .setHostRepoId(hostBase.getRepoId())
            .build());
  }

  @BeforeEach
  void beforeEach() throws Exception {
    insertSimpleResources(ImmutableList.of(makeRegistrar1(), makeRegistrar2()));

    // Two real registrars have been created by loadInitialData(), named "New Registrar" and "The
    // Registrar". Create one included registrar (external_monitoring) and two excluded ones.
    Registrar monitoringRegistrar =
        persistNewRegistrar("monitoring", "monitoring", Registrar.Type.MONITORING, null);
    Registrar testRegistrar = persistNewRegistrar("test", "test", Registrar.Type.TEST, null);
    Registrar externalMonitoringRegistrar =
        persistNewRegistrar(
            "externalmonitor", "external_monitoring", Registrar.Type.EXTERNAL_MONITORING, 9997L);
    // Set Registrar states which are required for reporting.
    tm().transact(
            () ->
                tm().putAll(
                        ImmutableList.of(
                            externalMonitoringRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            testRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            monitoringRegistrar.asBuilder().setState(State.ACTIVE).build())));

    createTld("soy");
    createTld("fun");
    createTld("cat");

    tm().transact(
            () -> {
              tm().put(Cursor.create(CursorType.BRDA, now, Registry.get("soy")));
              tm().put(Cursor.create(RDE_STAGING, now, Registry.get("soy")));
              RdeRevision.saveRevision("soy", now, THIN, 0);
              RdeRevision.saveRevision("soy", now, FULL, 0);
            });

    // This contact is never referenced.
    persistContactHistory(persistActiveContact("contactX"));
    ContactResource contact1 = persistActiveContact("contact1234");
    persistContactHistory(contact1);
    ContactResource contact2 = persistActiveContact("contact456");
    persistContactHistory(contact2);

    // This host is never referenced.
    persistHostHistory(persistActiveHost("ns0.domain.tld"));
    HostResource host1 = persistActiveHost("ns1.external.tld");
    persistHostHistory(host1);
    DomainBase helloDomain =
        persistEppResource(
            newDomainBase("hello.soy", contact1)
                .asBuilder()
                .addNameserver(host1.createVKey())
                .build());
    persistDomainHistory(helloDomain);
    persistHostHistory(persistActiveHost("not-used-subordinate.hello.soy"));
    HostResource host2 = persistActiveHost("ns1.hello.soy");
    persistHostHistory(host2);
    DomainBase kittyDomain =
        persistEppResource(
            newDomainBase("kitty.fun", contact2)
                .asBuilder()
                .addNameservers(ImmutableSet.of(host1.createVKey(), host2.createVKey()))
                .build());
    persistDomainHistory(kittyDomain);
    // Should not appear because the TLD is not included in a pending deposit.
    persistDomainHistory(persistEppResource(newDomainBase("lol.cat", contact1)));
    // To be deleted.
    DomainBase deletedDomain = persistActiveDomain("deleted.soy");
    persistDomainHistory(deletedDomain);

    // Advance time
    clock.advanceOneMilli();
    persistDomainHistory(deletedDomain.asBuilder().setDeletionTime(clock.nowUtc()).build());
    kittyDomain = kittyDomain.asBuilder().setDomainName("cat.fun").build();
    persistDomainHistory(kittyDomain);
    ContactResource contact3 = persistActiveContact("contact789");
    persistContactHistory(contact3);
    // This is a subordinate domain in TLD .cat, which is not included in any pending deposit. But
    // it should still be included as a subordinate host in the pendign deposit for .soy.
    HostResource host3 = persistActiveHost("ns1.lol.cat");
    persistHostHistory(host3);
    persistDomainHistory(
        helloDomain
            .asBuilder()
            .addContacts(
                ImmutableSet.of(
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, contact3.createVKey())))
            .addNameserver(host3.createVKey())
            .build());
    // contact456 is renamed to contactABC.
    persistContactHistory(contact2.asBuilder().setContactId("contactABC").build());
    // ns1.hello.soy is renamed to ns2.hello.soy
    persistHostHistory(host2.asBuilder().setHostName("ns2.hello.soy").build());

    // Set the clock to 2000-01-02, any change after hereafter should not show up in the
    // resulting deposit fragments.
    clock.advanceBy(Duration.standardDays(2));
    persistDomainHistory(kittyDomain.asBuilder().setDeletionTime(clock.nowUtc()).build());
    ContactResource futureContact = persistActiveContact("future-contact");
    persistContactHistory(futureContact);
    HostResource futureHost = persistActiveHost("ns1.future.tld");
    persistHostHistory(futureHost);
    persistDomainHistory(
        persistEppResource(
            newDomainBase("future.soy", futureContact)
                .asBuilder()
                .setNameservers(futureHost.createVKey())
                .build()));
    // contactABC is renamed to contactXYZ.
    persistContactHistory(contact2.asBuilder().setContactId("contactXYZ").build());
    // ns2.hello.soy is renamed to ns3.hello.soy
    persistHostHistory(host2.asBuilder().setHostName("ns3.hello.soy").build());

    options.setPendings(encodePendingDeposits(pendings));
    // The EPP resources created in tests do not have all the fields populated, using a STRICT
    // validation mode will result in a lot of warnings during marshalling.
    options.setValidationMode("LENIENT");
    options.setStagingKey(
        BaseEncoding.base64Url().encode(PgpHelper.convertPublicKeyToBytes(encryptionKey)));
    options.setRdeStagingBucket("gcs-bucket");
    options.setJobName("rde-job");
    rdePipeline = new RdePipeline(options, gcsUtils, cloudTasksHelper.getTestCloudTasksUtils());
  }

  @Test
  void testSuccess_encodeAndDecodePendingDeposits() throws Exception {
    String encodedString = encodePendingDeposits(pendings);
    assertThat(decodePendingDeposits(encodedString)).isEqualTo(pendings);
  }

  @Test
  void testFailure_pendingDepositsWithDifferentWatermarks() throws Exception {
    options.setPendings(
        encodePendingDeposits(
            ImmutableSet.of(
                PendingDeposit.create("soy", now, FULL, RDE_STAGING, Duration.standardDays(1)),
                PendingDeposit.create(
                    "soy", now.plusSeconds(1), THIN, RDE_STAGING, Duration.standardDays(1)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RdePipeline(options, gcsUtils, cloudTasksHelper.getTestCloudTasksUtils()));
  }

  @Test
  void testSuccess_createFragments() {
    PAssert.that(rdePipeline.createFragments(pipeline))
        .satisfies(
            kvs -> {
              kvs.forEach(
                  kv -> {
                    // Registrar fragments.
                    assertThat(
                            getFragmentForType(kv, REGISTRAR)
                                .map(getXmlElement(REGISTRAR_NAME_PATTERN))
                                .collect(toImmutableSet()))
                        // The same registrars are attached to all the pending deposits.
                        .containsExactly("New Registrar", "The Registrar", "external_monitoring");
                    // Domain fragments.
                    if (kv.getKey().tld().equals("soy")) {
                      assertThat(
                              getFragmentForType(kv, DOMAIN)
                                  .map(getXmlElement(DOMAIN_NAME_PATTERN))
                                  .collect(toImmutableSet()))
                          .containsExactly("hello.soy");
                    } else {
                      assertThat(
                              getFragmentForType(kv, DOMAIN)
                                  .map(getXmlElement(DOMAIN_NAME_PATTERN))
                                  .collect(toImmutableSet()))
                          .containsExactly("cat.fun");
                    }
                    if (kv.getKey().mode().equals(FULL)) {
                      // Contact fragments for hello.soy.
                      if (kv.getKey().tld().equals("soy")) {
                        assertThat(
                                getFragmentForType(kv, CONTACT)
                                    .map(getXmlElement(CONTACT_ID_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("contact1234", "contact789");
                        // Host fragments for hello.soy.
                        assertThat(
                                getFragmentForType(kv, HOST)
                                    .map(getXmlElement(HOST_NAME_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("ns1.external.tld", "ns1.lol.cat");
                      } else {
                        // Contact fragments for cat.fun.
                        assertThat(
                                getFragmentForType(kv, CONTACT)
                                    .map(getXmlElement(CONTACT_ID_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("contactABC");
                        // Host fragments for cat.soy.
                        assertThat(
                                getFragmentForType(kv, HOST)
                                    .map(getXmlElement(HOST_NAME_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("ns1.external.tld", "ns2.hello.soy");
                      }
                    } else {
                      // BRDA does not contain contact or hosts.
                      assertThat(
                              Streams.stream(kv.getValue())
                                  .anyMatch(
                                      fragment ->
                                          fragment.type().equals(CONTACT)
                                              || fragment.type().equals(HOST)))
                          .isFalse();
                    }
                  });
              return null;
            });
    pipeline.run().waitUntilFinish();
  }

  // The GCS folder listing can be a bit flaky, so retry if necessary
  @RetryingTest(4)
  void testSuccess_persistData() throws Exception {
    PendingDeposit brdaKey =
        PendingDeposit.create("soy", now, THIN, CursorType.BRDA, Duration.standardDays(1));
    PendingDeposit rdeKey =
        PendingDeposit.create("soy", now, FULL, RDE_STAGING, Duration.standardDays(1));

    verifyFiles(ImmutableMap.of(brdaKey, brdaFragments, rdeKey, rdeFragments), false);

    assertThat(gcsUtils.listFolderObjects("gcs-bucket", "rde-job/"))
        .containsExactly(
            "soy_2000-01-01_thin_S1_R1.xml.length",
            "soy_2000-01-01_thin_S1_R1.xml.ghostryde",
            "soy_2000-01-01_full_S1_R1.xml.length",
            "soy_2000-01-01_full_S1_R1.xml.ghostryde",
            "soy_2000-01-01_full_S1_R1-report.xml.ghostryde");

    assertThat(loadCursorTime(CursorType.BRDA))
        .isEquivalentAccordingToCompareTo(now.plus(Duration.standardDays(1)));
    assertThat(loadRevision(now, THIN)).isEqualTo(1);

    assertThat(loadCursorTime(RDE_STAGING))
        .isEquivalentAccordingToCompareTo(now.plus(Duration.standardDays(1)));
    assertThat(loadRevision(now, FULL)).isEqualTo(1);
    cloudTasksHelper.assertTasksEnqueued(
        "brda",
        new TaskMatcher()
            .url("/_dr/task/brdaCopy")
            .service("backend")
            .param("tld", "soy")
            .param("watermark", now.toString())
            .param("prefix", "rde-job/"));
    cloudTasksHelper.assertTasksEnqueued(
        "rde-upload",
        new TaskMatcher()
            .url("/_dr/task/rdeUpload")
            .service("backend")
            .param("tld", "soy")
            .param("prefix", "rde-job/"));
  }

  // The GCS folder listing can be a bit flaky, so retry if necessary
  @RetryingTest(4)
  void testSuccess_persistData_manual() throws Exception {
    PendingDeposit brdaKey = PendingDeposit.createInManualOperation("soy", now, THIN, "test/", 0);
    PendingDeposit rdeKey = PendingDeposit.createInManualOperation("soy", now, FULL, "test/", 0);

    verifyFiles(ImmutableMap.of(brdaKey, brdaFragments, rdeKey, rdeFragments), true);

    assertThat(gcsUtils.listFolderObjects("gcs-bucket", "manual/test/rde-job/"))
        .containsExactly(
            "soy_2000-01-01_thin_S1_R0.xml.length",
            "soy_2000-01-01_thin_S1_R0.xml.ghostryde",
            "soy_2000-01-01_full_S1_R0.xml.length",
            "soy_2000-01-01_full_S1_R0.xml.ghostryde",
            "soy_2000-01-01_full_S1_R0-report.xml.ghostryde");

    assertThat(loadCursorTime(CursorType.BRDA)).isEquivalentAccordingToCompareTo(now);
    assertThat(loadRevision(now, THIN)).isEqualTo(0);

    assertThat(loadCursorTime(RDE_STAGING)).isEquivalentAccordingToCompareTo(now);
    assertThat(loadRevision(now, FULL)).isEqualTo(0);
    cloudTasksHelper.assertNoTasksEnqueued("brda", "rde-upload");
  }

  private void verifyFiles(
      ImmutableMap<PendingDeposit, Iterable<DepositFragment>> input, boolean manual)
      throws Exception {
    PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> fragments =
        pipeline.apply("Create Input", Create.of(input));
    rdePipeline.persistData(fragments);
    pipeline.run().waitUntilFinish();

    String prefix = manual ? "manual/test/rde-job/" : "rde-job/";
    String revision = manual ? "R0" : "R1";

    // BRDA
    String brdaOutputFile =
        decryptGhostrydeGcsFile(prefix + "soy_2000-01-01_thin_S1_" + revision + ".xml.ghostryde");
    assertThat(brdaOutputFile)
        .isEqualTo(
            readResourceUtf8(this.getClass(), "reducer_brda.xml")
                .replace("%RESEND%", manual ? "" : " resend=\"1\""));
    compareLength(brdaOutputFile, prefix + "soy_2000-01-01_thin_S1_" + revision + ".xml.length");

    // RDE
    String rdeOutputFile =
        decryptGhostrydeGcsFile(prefix + "soy_2000-01-01_full_S1_" + revision + ".xml.ghostryde");
    assertThat(rdeOutputFile)
        .isEqualTo(
            readResourceUtf8(RdePipelineTest.class, "reducer_rde.xml")
                .replace("%RESEND%", manual ? "" : " resend=\"1\""));
    compareLength(rdeOutputFile, prefix + "soy_2000-01-01_full_S1_" + revision + ".xml.length");
    assertThat(
            decryptGhostrydeGcsFile(
                prefix + "soy_2000-01-01_full_S1_" + revision + "-report.xml.ghostryde"))
        .isEqualTo(
            readResourceUtf8(RdePipelineTest.class, "reducer_rde_report.xml")
                .replace("%RESEND%", manual ? "0" : "1"));
  }

  private String decryptGhostrydeGcsFile(String filename) throws IOException {
    return new String(
        Ghostryde.decode(gcsUtils.readBytesFrom(BlobId.of("gcs-bucket", filename)), decryptionKey),
        UTF_8);
  }

  private void compareLength(String outputFile, String lengthFilename) throws IOException {
    assertThat(String.valueOf(outputFile.getBytes(UTF_8).length))
        .isEqualTo(
            new String(gcsUtils.readBytesFrom(BlobId.of("gcs-bucket", lengthFilename)), UTF_8));
  }

  private static int loadRevision(DateTime now, RdeMode mode) {
    return tm().transact(
            () ->
                tm().loadByKey(
                        VKey.createSql(
                            RdeRevision.class,
                            RdeRevisionId.create("soy", now.toLocalDate(), mode)))
                    .getRevision());
  }

  private static DateTime loadCursorTime(CursorType type) {
    return tm().transact(() -> tm().loadByKey(Cursor.createVKey(type, "soy")).getCursorTime());
  }

  private static Function<DepositFragment, String> getXmlElement(String pattern) {
    return (fragment) -> {
      Matcher matcher = Pattern.compile(pattern).matcher(fragment.xml());
      checkState(matcher.find(), "Missing %s in xml.", pattern);
      return matcher.group(1);
    };
  }

  private static Stream<DepositFragment> getFragmentForType(
      KV<PendingDeposit, Iterable<DepositFragment>> kv, RdeResourceType type) {
    return Streams.stream(kv.getValue()).filter(fragment -> fragment.type().equals(type));
  }
}
