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
import static google.registry.persistence.transaction.JpaTransactionManagerExtension.makeRegistrar1;
import static google.registry.persistence.transaction.JpaTransactionManagerExtension.makeRegistrar2;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeResourceType.DOMAIN;
import static google.registry.rde.RdeResourceType.HOST;
import static google.registry.rde.RdeResourceType.REGISTRAR;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
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
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeRevision;
import google.registry.model.rde.RdeRevision.RdeRevisionId;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.rde.DepositFragment;
import google.registry.rde.Ghostryde;
import google.registry.rde.PendingDeposit;
import google.registry.rde.RdeResourceType;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;

/** Unit tests for {@link RdePipeline}. */
public class RdePipelineTest {

  private static final String REGISTRAR_NAME_PATTERN =
      "<rdeRegistrar:name>(.*)</rdeRegistrar:name>";

  private static final String DOMAIN_NAME_PATTERN = "<rdeDomain:name>(.*)</rdeDomain:name>";

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
          DepositFragment.create(DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(REGISTRAR, "<rdeRegistrar:registrar/>\n", ""));

  private final ImmutableList<DepositFragment> rdeFragments =
      ImmutableList.of(
          DepositFragment.create(DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(REGISTRAR, "<rdeRegistrar:registrar/>\n", ""),
          DepositFragment.create(HOST, "<rdeHost:host/>\n", ""));

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  private final PGPPublicKey encryptionKey =
      new FakeKeyringModule().get().getRdeStagingEncryptionKey();

  private final PGPPrivateKey decryptionKey =
      new FakeKeyringModule().get().getRdeStagingDecryptionKey();

  private final RdePipelineOptions options =
      PipelineOptionsFactory.create().as(RdePipelineOptions.class);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.fromOptions(options).enableAbandonedNodeEnforcement(true);

  private RdePipeline rdePipeline;

  private DomainHistory persistDomainHistory(DomainBase domain) {
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
            .build());
  }

  @BeforeEach
  void beforeEach() throws Exception {
    persistResources(ImmutableList.of(makeRegistrar1(), makeRegistrar2()));

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
              tm().put(Cursor.createScoped(CursorType.BRDA, now, Tld.get("soy")));
              tm().put(Cursor.createScoped(RDE_STAGING, now, Tld.get("soy")));
              RdeRevision.saveRevision("soy", now, THIN, 0);
              RdeRevision.saveRevision("soy", now, FULL, 0);
            });

    // This host is never referenced.
    persistHostHistory(persistActiveHost("ns0.domain.tld"));
    Host host1 = persistActiveHost("ns1.external.tld");
    persistHostHistory(host1);
    Domain helloDomain =
        persistEppResource(
            newDomain("hello.soy").asBuilder().addNameserver(host1.createVKey()).build());
    persistDomainHistory(helloDomain);
    persistHostHistory(persistActiveHost("not-used-subordinate.hello.soy"));
    Host host2 = persistActiveHost("ns1.hello.soy");
    persistHostHistory(host2);

    // This domain has no registrant.
    Domain kittyDomain =
        persistEppResource(
            newDomain("kitty.fun")
                .asBuilder()
                .addNameservers(ImmutableSet.of(host1.createVKey(), host2.createVKey()))
                .build());
    persistDomainHistory(kittyDomain);
    // Should not appear because the TLD is not included in a pending deposit.
    persistDomainHistory(persistEppResource(newDomain("lol.cat")));
    // To be deleted.
    Domain deletedDomain = persistActiveDomain("deleted.soy");
    persistDomainHistory(deletedDomain);

    // Advance time
    clock.advanceOneMilli();
    persistDomainHistory(deletedDomain.asBuilder().setDeletionTime(clock.nowUtc()).build());
    kittyDomain = kittyDomain.asBuilder().setDomainName("cat.fun").build();
    persistDomainHistory(kittyDomain);

    // This is a subordinate domain in TLD .cat, which is not included in any pending deposit. But
    // it should still be included as a subordinate host in the pendign deposit for .soy.
    Host host3 = persistActiveHost("ns1.lol.cat");
    persistHostHistory(host3);
    persistDomainHistory(
        helloDomain
            .asBuilder()
            .addNameserver(host3.createVKey())
            .build());
    // ns1.hello.soy is renamed to ns2.hello.soy
    persistHostHistory(host2.asBuilder().setHostName("ns2.hello.soy").build());

    // Set the clock to 2000-01-02, any change after hereafter should not show up in the
    // resulting deposit fragments.
    clock.advanceBy(Duration.standardDays(2));
    persistDomainHistory(kittyDomain.asBuilder().setDeletionTime(clock.nowUtc()).build());
    Host futureHost = persistActiveHost("ns1.future.tld");
    persistHostHistory(futureHost);
    persistDomainHistory(
        persistEppResource(
            newDomain("future.soy").asBuilder().setNameservers(futureHost.createVKey()).build()));
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
                    ImmutableSet<DepositFragment> domainFrags =
                        getFragmentForType(kv, DOMAIN).collect(toImmutableSet());
                    assertThat(domainFrags).hasSize(1);
                    if ("fun".equals(kv.getKey().tld())) {
                      // Note that this fragment contains no registrant (which is valid).
                      assertThat(domainFrags.stream().findFirst().get().xml().strip())
                          .isEqualTo(
                              """
                              <rdeDomain:domain>
                                  <rdeDomain:name>cat.fun</rdeDomain:name>
                                  <rdeDomain:roid>F-FUN</rdeDomain:roid>
                                  <rdeDomain:uName>cat.fun</rdeDomain:uName>
                                  <rdeDomain:status s="ok"/>
                                  <rdeDomain:ns>
                                      <domain:hostObj>ns1.external.tld</domain:hostObj>
                                      <domain:hostObj>ns1.hello.soy</domain:hostObj>
                                  </rdeDomain:ns>
                                  <rdeDomain:clID>TheRegistrar</rdeDomain:clID>
                                  <rdeDomain:crRr>TheRegistrar</rdeDomain:crRr>
                                  <rdeDomain:crDate>1970-01-01T00:00:00Z</rdeDomain:crDate>
                                  <rdeDomain:exDate>294247-01-10T04:00:54Z</rdeDomain:exDate>
                              </rdeDomain:domain>\
                              """);
                    }
                    if (kv.getKey().mode().equals(FULL)) {
                      if ("soy".equals(kv.getKey().tld())) {
                        // Host fragments for hello.soy.
                        assertThat(
                                getFragmentForType(kv, HOST)
                                    .map(getXmlElement(HOST_NAME_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("ns1.external.tld", "ns1.lol.cat");

                        // Domain fragments for hello.soy: Note that this contains a registrant.
                        assertThat(domainFrags.stream().findFirst().get().xml().strip())
                            .isEqualTo(
                                """
                                <rdeDomain:domain>
                                    <rdeDomain:name>hello.soy</rdeDomain:name>
                                    <rdeDomain:roid>8-SOY</rdeDomain:roid>
                                    <rdeDomain:uName>hello.soy</rdeDomain:uName>
                                    <rdeDomain:status s="ok"/>
                                    <rdeDomain:ns>
                                        <domain:hostObj>ns1.external.tld</domain:hostObj>
                                        <domain:hostObj>ns1.lol.cat</domain:hostObj>
                                    </rdeDomain:ns>
                                    <rdeDomain:clID>TheRegistrar</rdeDomain:clID>
                                    <rdeDomain:crRr>TheRegistrar</rdeDomain:crRr>
                                    <rdeDomain:crDate>1970-01-01T00:00:00Z</rdeDomain:crDate>
                                    <rdeDomain:exDate>294247-01-10T04:00:54Z</rdeDomain:exDate>
                                </rdeDomain:domain>\
                                """);
                      } else {
                        // Host fragments for cat.soy.
                        assertThat(
                                getFragmentForType(kv, HOST)
                                    .map(getXmlElement(HOST_NAME_PATTERN))
                                    .collect(toImmutableSet()))
                            .containsExactly("ns1.external.tld", "ns2.hello.soy");
                      }
                    } else {
                      // BRDA does not contain hosts.
                      assertThat(
                              Streams.stream(kv.getValue())
                                  .anyMatch(fragment -> fragment.type().equals(HOST)))
                          .isFalse();

                      // Domain fragments for hello.soy.
                      assertThat(domainFrags.stream().findFirst().get().xml().strip())
                          .isEqualTo(
                              """
                              <rdeDomain:domain>
                                  <rdeDomain:name>hello.soy</rdeDomain:name>
                                  <rdeDomain:roid>8-SOY</rdeDomain:roid>
                                  <rdeDomain:uName>hello.soy</rdeDomain:uName>
                                  <rdeDomain:status s="ok"/>
                                  <rdeDomain:ns>
                                      <domain:hostObj>ns1.external.tld</domain:hostObj>
                                      <domain:hostObj>ns1.lol.cat</domain:hostObj>
                                  </rdeDomain:ns>
                                  <rdeDomain:clID>TheRegistrar</rdeDomain:clID>
                                  <rdeDomain:crRr>TheRegistrar</rdeDomain:crRr>
                                  <rdeDomain:crDate>1970-01-01T00:00:00Z</rdeDomain:crDate>
                                  <rdeDomain:exDate>294247-01-10T04:00:54Z</rdeDomain:exDate>
                              </rdeDomain:domain>\
                              """);
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
            .path("/_dr/task/brdaCopy")
            .service("backend")
            .param("tld", "soy")
            .param("watermark", now.toString())
            .param("prefix", "rde-job/"));
    cloudTasksHelper.assertTasksEnqueued(
        "rde-upload",
        new TaskMatcher()
            .path("/_dr/task/rdeUpload")
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
            readResourceUtf8(getClass(), "reducer_brda.xml")
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
                        VKey.create(
                            RdeRevision.class,
                            RdeRevisionId.create("soy", now.toLocalDate(), mode)))
                    .getRevision());
  }

  private static DateTime loadCursorTime(CursorType type) {
    return tm().transact(
            () -> tm().loadByKey(Cursor.createScopedVKey(type, Tld.get("soy"))).getCursorTime());
  }

  private static Function<DepositFragment, String> getXmlElement(String pattern) {
    return fragment -> {
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
