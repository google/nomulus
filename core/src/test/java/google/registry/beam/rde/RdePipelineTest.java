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
import static google.registry.beam.rde.RdePipeline.decodePendings;
import static google.registry.beam.rde.RdePipeline.encodePendings;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeResourceType.CONTACT;
import static google.registry.rde.RdeResourceType.DOMAIN;
import static google.registry.rde.RdeResourceType.HOST;
import static google.registry.rde.RdeResourceType.REGISTRAR;
import static google.registry.testing.AppEngineExtension.loadInitialData;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.io.BaseEncoding;
import google.registry.beam.TestPipelineExtension;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.PgpHelper;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeRevision;
import google.registry.model.rde.RdeRevision.RdeRevisionId;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registry.Registry;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.rde.DepositFragment;
import google.registry.rde.Ghostryde;
import google.registry.rde.PendingDeposit;
import google.registry.rde.RdeResourceType;
import google.registry.testing.DatastoreEntityExtension;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

  private final ImmutableSetMultimap<String, PendingDeposit> pendings =
      ImmutableSetMultimap.of(
          "soy",
          PendingDeposit.create("soy", now, FULL, RDE_STAGING, standardDays(1)),
          "soy",
          PendingDeposit.create("soy", now, THIN, RDE_STAGING, standardDays(1)),
          "fun",
          PendingDeposit.create("fun", now, FULL, RDE_STAGING, standardDays(1)));

  private final ImmutableList<DepositFragment> brdaFragments =
      ImmutableList.of(
          DepositFragment.create(RdeResourceType.DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(RdeResourceType.REGISTRAR, "<rdeRegistrar:registrar/>\n", ""));

  private final ImmutableList<DepositFragment> rdeFragments =
      ImmutableList.of(
          DepositFragment.create(RdeResourceType.DOMAIN, "<rdeDomain:domain/>\n", ""),
          DepositFragment.create(RdeResourceType.REGISTRAR, "<rdeRegistrar:registrar/>\n", ""),
          DepositFragment.create(RdeResourceType.CONTACT, "<rdeContact:contact/>\n", ""),
          DepositFragment.create(RdeResourceType.HOST, "<rdeHost:host/>\n", ""));

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

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
      new JpaTestRules.Builder().withClock(clock).buildIntegrationTestRule();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.fromOptions(options).enableAbandonedNodeEnforcement(true);

  private RdePipeline rdePipeline;

  @BeforeEach
  void beforeEach() throws Exception {
    TransactionManagerFactory.setTmForTest(jpaTm());
    loadInitialData();

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
              tm().put(Cursor.create(CursorType.RDE_STAGING, now, Registry.get("soy")));
              RdeRevision.saveRevision("soy", now, THIN, 0);
              RdeRevision.saveRevision("soy", now, FULL, 0);
            });

    // Also persists a "contact1234" contact in the process.
    persistActiveDomain("hello.soy");
    persistActiveDomain("kitty.fun");
    // Should not appear anywhere.
    persistActiveDomain("lol.cat");
    persistDeletedDomain("deleted.soy", DateTime.parse("1999-12-30TZ"));

    persistActiveContact("contact456");

    HostResource host = persistEppResource(newHostResource("old.host.test"));
    // Set the clock to 2000-01-02, the updated host should NOT show up in RDE.
    clock.advanceBy(Duration.standardDays(2));
    persistEppResource(host.asBuilder().setHostName("new.host.test").build());

    options.setPendings(encodePendings(pendings));
    // The EPP resources created in tests do not have all the fields populated, using a STRICT
    // validation mode will result in a lot of warnings during marshalling.
    options.setValidationMode("LENIENT");
    options.setStagingKey(
        BaseEncoding.base64Url().encode(PgpHelper.convertPublicKeyToBytes(encryptionKey)));
    options.setGcsBucket("gcs-bucket");
    options.setJobName("rde-job");
    rdePipeline = new RdePipeline(options, gcsUtils);
  }

  @AfterEach
  void afterEach() {
    TransactionManagerFactory.removeTmOverrideForTest();
  }

  @Test
  void testSuccess_encodeAndDecodePendingsMap() throws Exception {
    String encodedString = encodePendings(pendings);
    assertThat(decodePendings(encodedString)).isEqualTo(pendings);
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
                    assertThat(
                            getFragmentForType(kv, DOMAIN)
                                .map(getXmlElement(DOMAIN_NAME_PATTERN))
                                .anyMatch(
                                    domain ->
                                        // Deleted domain should not be included
                                        domain.equals("deleted.soy")
                                            // Only domains on the pending deposit's tld should
                                            // appear.
                                            || !kv.getKey()
                                                .tld()
                                                .equals(
                                                    Iterables.get(
                                                        Splitter.on('.').split(domain), 1))))
                        .isFalse();
                    if (kv.getKey().mode().equals(FULL)) {
                      // Contact fragments.
                      assertThat(
                              getFragmentForType(kv, CONTACT)
                                  .map(getXmlElement(CONTACT_ID_PATTERN))
                                  .collect(toImmutableSet()))
                          // The same contacts are attached too all pending deposits.
                          .containsExactly("contact1234", "contact456");
                      // Host fragments.
                      assertThat(
                              getFragmentForType(kv, HOST)
                                  .map(getXmlElement(HOST_NAME_PATTERN))
                                  .collect(toImmutableSet()))
                          // Should load the resource before update.
                          .containsExactly("old.host.test");
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

  @Test
  void testSuccess_persistData() throws Exception {
    PendingDeposit brdaKey =
        PendingDeposit.create("soy", now, THIN, CursorType.BRDA, Duration.standardDays(1));
    PendingDeposit rdeKey =
        PendingDeposit.create("soy", now, FULL, CursorType.RDE_STAGING, Duration.standardDays(1));

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

    assertThat(loadCursorTime(CursorType.RDE_STAGING))
        .isEquivalentAccordingToCompareTo(now.plus(Duration.standardDays(1)));
    assertThat(loadRevision(now, FULL)).isEqualTo(1);
  }

  @Test
  void testSuccess_persistData_manual() throws Exception {
    PendingDeposit brdaKey = PendingDeposit.createInManualOperation("soy", now, THIN, "test/", 0);
    PendingDeposit rdeKey = PendingDeposit.createInManualOperation("soy", now, FULL, "test/", 0);

    verifyFiles(ImmutableMap.of(brdaKey, brdaFragments, rdeKey, rdeFragments), true);

    assertThat(gcsUtils.listFolderObjects("gcs-bucket", "rde-job/"))
        .containsExactly(
            "manual/test/soy_2000-01-01_thin_S1_R0.xml.length",
            "manual/test/soy_2000-01-01_thin_S1_R0.xml.ghostryde",
            "manual/test/soy_2000-01-01_full_S1_R0.xml.length",
            "manual/test/soy_2000-01-01_full_S1_R0.xml.ghostryde",
            "manual/test/soy_2000-01-01_full_S1_R0-report.xml.ghostryde");

    assertThat(loadCursorTime(CursorType.BRDA)).isEquivalentAccordingToCompareTo(now);
    assertThat(loadRevision(now, THIN)).isEqualTo(0);

    assertThat(loadCursorTime(CursorType.RDE_STAGING)).isEquivalentAccordingToCompareTo(now);
    assertThat(loadRevision(now, FULL)).isEqualTo(0);
  }

  private void verifyFiles(
      ImmutableMap<PendingDeposit, Iterable<DepositFragment>> input, boolean manual)
      throws Exception {
    PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> fragments =
        pipeline.apply("Create Input", Create.of(input));
    rdePipeline.persistData(fragments);
    pipeline.run().waitUntilFinish();

    String prefix = manual ? "rde-job/manual/test/" : "rde-job/";
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
