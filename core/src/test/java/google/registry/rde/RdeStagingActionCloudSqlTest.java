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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertAtLeastOneTaskIsEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.BeamActionTestBase;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.Keyring;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.tld.Registry;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rde.XjcRdeContentType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderCount;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.bind.JAXBElement;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdeStagingAction} in Cloud SQL. */
public class RdeStagingActionCloudSqlTest extends BeamActionTestBase {

  private static final BlobId XML_FILE =
      BlobId.of("rde-bucket", "lol_2000-01-01_full_S1_R0.xml.ghostryde");
  private static final BlobId LENGTH_FILE =
      BlobId.of("rde-bucket", "lol_2000-01-01_full_S1_R0.xml.length");

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock();
  private final FakeResponse response = new FakeResponse();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final List<? super XjcRdeContentType> alreadyExtracted = new ArrayList<>();

  private static PGPPublicKey encryptKey;
  private static PGPPrivateKey decryptKey;

  private RdeStagingAction action = new RdeStagingAction();

  @BeforeAll
  static void beforeAll() {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      encryptKey = keyring.getRdeStagingEncryptionKey();
      decryptKey = keyring.getRdeStagingDecryptionKey();
    }
    TransactionManagerFactory.setTmForTest(jpaTm());
  }

  @AfterAll
  static void afterAll() {
    TransactionManagerFactory.removeTmOverrideForTest();
  }

  @BeforeEach
  void beforeEach() {
    action.clock = clock;
    action.lenient = false;
    action.pendingDepositChecker = new PendingDepositChecker();
    action.pendingDepositChecker.brdaDayOfWeek = DateTimeConstants.TUESDAY;
    action.pendingDepositChecker.brdaInterval = Duration.standardDays(7);
    action.pendingDepositChecker.clock = clock;
    action.pendingDepositChecker.rdeInterval = Duration.standardDays(1);
    action.gcsUtils = gcsUtils;
    action.response = response;
    action.transactionCooldown = Duration.ZERO;
    action.directory = Optional.empty();
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of();
    action.revision = Optional.empty();
  }

  @Test
  void testRun_modeInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.modeStrings = ImmutableSet.of("full");
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testRun_tldInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.tlds = ImmutableSet.of("tld");
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testRun_watermarkInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testRun_revisionInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.revision = Optional.of(42);
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testRun_noTlds_returns204() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  void testRun_tldWithoutEscrowEnabled_returns204() {
    createTld("lol");
    persistResource(Registry.get("lol").asBuilder().setEscrowEnabled(false).build());
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  void testRun_tldWithEscrowEnabled_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("/_ah/pipeline/status.html?root=");
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  @Test
  void testRun_withinTransactionCooldown_getsExcludedAndReturns204() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:04:59Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  void testRun_afterTransactionCooldown_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:05:00Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  @Test
  void testManualRun_emptyMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_invalidMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full", "thing");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_emptyTld_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_emptyWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of();
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_nonDayStartWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01T01:36:45Z"));
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_invalidRevision_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01T00:00:00Z"));
    action.revision = Optional.of(-1);
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  void testManualRun_validParameters_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("_ah/pipeline/status.html?root=");
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  private String readXml(String objectName) throws IOException, PGPException {
    BlobId file = BlobId.of("rde-bucket", objectName);
    return new String(Ghostryde.decode(gcsUtils.readBytesFrom(file), decryptKey), UTF_8);
  }

  private <T extends XjcRdeContentType>
      T extractAndRemoveContentWithType(Class<T> type, XjcRdeDeposit deposit) {
    for (JAXBElement<? extends XjcRdeContentType> content : deposit.getContents().getContents()) {
      XjcRdeContentType piece = content.getValue();
      if (type.isInstance(piece) && !alreadyExtracted.contains(piece)) {
        alreadyExtracted.add(piece);
        return type.cast(piece);
      }
    }
    throw new AssertionError("Expected deposit to contain another " + type.getSimpleName());
  }

  private static void createTldWithEscrowEnabled(final String tld) {
    createTld(tld);
    persistResource(Registry.get(tld).asBuilder().setEscrowEnabled(true).build());
  }

  private static ImmutableMap<String, Long> mapifyCounts(XjcRdeHeader header) {
    ImmutableMap.Builder<String, Long> builder = new ImmutableMap.Builder<>();
    for (XjcRdeHeaderCount count : header.getCounts()) {
      builder.put(count.getUri(), count.getValue());
    }
    return builder.build();
  }

  private void setCursor(
      final Registry registry, final CursorType cursorType, final DateTime value) {
    clock.advanceOneMilli();
    tm().transact(() -> tm().put(Cursor.create(cursorType, value, registry)));
  }

  public static <T> T unmarshal(Class<T> clazz, byte[] xml) throws XmlException {
    return XjcXmlTransformer.unmarshal(clazz, new ByteArrayInputStream(xml));
  }
}
