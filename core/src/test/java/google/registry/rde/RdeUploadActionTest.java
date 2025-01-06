// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD_SFTP;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static google.registry.testing.GpgSystemCommandExtension.GPG_BINARY;
import static google.registry.testing.SystemInfo.hasCommand;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.Keyring;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.rde.JSchSshSession.JSchSshSessionFactory;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.RequestParameters;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.GpgSystemCommandExtension;
import google.registry.testing.Lazies;
import google.registry.testing.sftp.SftpServerExtension;
import google.registry.util.Retrier;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.OngoingStubbing;

/** Unit tests for {@link RdeUploadAction}. */
public class RdeUploadActionTest {

  private static final ByteSource REPORT_XML = RdeTestData.loadBytes("report.xml");
  private static final ByteSource DEPOSIT_XML = RdeTestData.loadBytes("deposit_full.xml");
  private static final String JOB_PREFIX = "rde-2010-10-17t00-00-00z";

  private static final BlobId GHOSTRYDE_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R0.xml.ghostryde");
  private static final BlobId LENGTH_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R0.xml.length");
  private static final BlobId REPORT_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R0-report.xml.ghostryde");
  private static final BlobId GHOSTRYDE_FILE_WITH_PREFIX =
      BlobId.of("bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.xml.ghostryde");
  private static final BlobId LENGTH_FILE_WITH_PREFIX =
      BlobId.of("bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.xml.length");
  private static final BlobId REPORT_FILE_WITH_PREFIX =
      BlobId.of("bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0-report.xml.ghostryde");

  private static final BlobId GHOSTRYDE_R1_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R1.xml.ghostryde");
  private static final BlobId LENGTH_R1_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R1.xml.length");
  private static final BlobId REPORT_R1_FILE =
      BlobId.of("bucket", "tld_2010-10-17_full_S1_R1-report.xml.ghostryde");

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  @RegisterExtension final SftpServerExtension sftpd = new SftpServerExtension();

  @SuppressWarnings("WeakerAccess")
  @TempDir
  File folder;

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  public final GpgSystemCommandExtension gpg =
      new GpgSystemCommandExtension(
          RdeTestData.loadBytes("pgp-public-keyring.asc"),
          RdeTestData.loadBytes("pgp-private-keyring-escrow.asc"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final PGPPublicKey encryptKey =
      new FakeKeyringModule().get().getRdeStagingEncryptionKey();
  private final FakeResponse response = new FakeResponse();
  private final EscrowTaskRunner runner = mock(EscrowTaskRunner.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2010-10-17TZ"));

  private RdeUploadAction createAction(URI uploadUrl) {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      RdeUploadAction action = new RdeUploadAction();
      action.clock = clock;
      action.gcsUtils = gcsUtils;
      action.lazyJsch =
          () ->
              JSchModule.provideJSch(
                  "user@ignored",
                  keyring.getRdeSshClientPrivateKey(),
                  keyring.getRdeSshClientPublicKey());
      action.jschSshSessionFactory = new JSchSshSessionFactory(standardSeconds(3));
      action.response = response;
      action.bucket = "bucket";
      action.interval = standardDays(1);
      action.timeout = standardSeconds(23);
      action.tld = "tld";
      action.sftpCooldown = standardSeconds(7);
      action.uploadUrl = uploadUrl;
      action.receiverKey = keyring.getRdeReceiverKey();
      action.signingKey = keyring.getRdeSigningKey();
      action.stagingDecryptionKey = keyring.getRdeStagingDecryptionKey();
      action.runner = runner;
      action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
      action.retrier = new Retrier(new FakeSleeper(clock), 3);
      action.prefix = Optional.empty();
      return action;
    }
  }

  private static JSch createThrowingJSchSpy(JSch jsch, int numTimesToThrow) throws JSchException {
    JSch jschSpy = spy(jsch);
    OngoingStubbing<Session> stubbing =
        when(jschSpy.getSession(anyString(), anyString(), anyInt()));
    for (int i = 0; i < numTimesToThrow; i++) {
      stubbing = stubbing.thenThrow(new JSchException("The crow flies in square circles."));
    }
    stubbing.thenCallRealMethod();
    return jschSpy;
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    gcsUtils.createFromBytes(GHOSTRYDE_FILE, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(GHOSTRYDE_R1_FILE, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(LENGTH_FILE, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    gcsUtils.createFromBytes(LENGTH_R1_FILE, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    gcsUtils.createFromBytes(REPORT_FILE, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(REPORT_R1_FILE, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(
        GHOSTRYDE_FILE_WITH_PREFIX, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(
        LENGTH_FILE_WITH_PREFIX, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    gcsUtils.createFromBytes(
        REPORT_FILE_WITH_PREFIX, Ghostryde.encode(REPORT_XML.read(), encryptKey));

    tm().transact(
            () -> {
              RdeRevision.saveRevision("lol", DateTime.parse("2010-10-17TZ"), FULL, 0);
              RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 0);
            });
  }

  @Test
  void testSocketConnection() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    try (Socket socket = new Socket("localhost", port)) {
      assertThat(socket.isConnected()).isTrue();
    }
  }

  @Test
  void testRun() {
    createTld("lol");
    RdeUploadAction action = createAction(null);
    action.tld = "lol";
    action.run();
    verify(runner)
        .lockRunAndRollForward(
            action, Tld.get("lol"), standardSeconds(23), CursorType.RDE_UPLOAD, standardDays(1));
    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher().path(RdeReportAction.PATH).param(RequestParameters.PARAM_TLD, "lol"));
    verifyNoMoreInteractions(runner);
  }

  @Test
  void testRun_withPrefix() throws Exception {
    createTld("lol");
    RdeUploadAction action = createAction(null);
    action.prefix = Optional.of("job-name/");
    action.tld = "lol";
    action.run();
    verify(runner)
        .lockRunAndRollForward(
            action, Tld.get("lol"), standardSeconds(23), CursorType.RDE_UPLOAD, standardDays(1));
    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .path(RdeReportAction.PATH)
            .param(RequestParameters.PARAM_TLD, "lol")
            .param(RdeModule.PARAM_PREFIX, "job-name/"));
    verifyNoMoreInteractions(runner);
  }

  @Test
  void testRunWithLock_succeedsOnThirdTry() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    action.lazyJsch = Lazies.of(createThrowingJSchSpy(action.lazyJsch.get(), 2));
    action.runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    assertThat(folder.list())
        .asList()
        .containsExactly("tld_2010-10-17_full_S1_R0.ryde", "tld_2010-10-17_full_S1_R0.sig");
  }

  @Test
  void testRunWithLock_failsAfterThreeAttempts() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    action.lazyJsch = Lazies.of(createThrowingJSchSpy(action.lazyJsch.get(), 3));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown).hasMessageThat().contains("The crow flies in square circles.");
  }

  @Test
  void testRunWithLock_cannotGuessPrefix() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    gcsUtils.delete(GHOSTRYDE_FILE_WITH_PREFIX);
    gcsUtils.delete(LENGTH_FILE_WITH_PREFIX);
    gcsUtils.delete(REPORT_FILE_WITH_PREFIX);
    RdeUploadAction action = createAction(uploadUrl);
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("RDE deposit for TLD tld on 2010-10-17T00:00:00.000Z does not exist");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    assertThat(folder.list()).isEmpty();
  }

  @Test
  void testRunWithLock_copiesOnGcs_withPrefix() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    action.prefix = Optional.of(JOB_PREFIX + "-job-name/");
    gcsUtils.delete(GHOSTRYDE_FILE);
    gcsUtils.delete(LENGTH_FILE);
    gcsUtils.delete(REPORT_FILE);
    action.runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    // Assert that both files are written to SFTP and GCS, and that the contents are identical.
    String rydeFilename = "tld_2010-10-17_full_S1_R0.ryde";
    String rydeGcsFilename = JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.ryde";
    String sigFilename = "tld_2010-10-17_full_S1_R0.sig";
    String sigGcsFilename = JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.sig";
    assertThat(folder.list()).asList().containsExactly(rydeFilename, sigFilename);
    assertThat(gcsUtils.readBytesFrom(BlobId.of("bucket", rydeGcsFilename)))
        .isEqualTo(Files.toByteArray(new File(folder, rydeFilename)));
    assertThat(gcsUtils.readBytesFrom(BlobId.of("bucket", sigGcsFilename)))
        .isEqualTo(Files.toByteArray(new File(folder, sigFilename)));
  }

  @Test
  void testRunWithLock_copiesOnGcs_withoutPrefix() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    gcsUtils.delete(GHOSTRYDE_FILE);
    gcsUtils.delete(LENGTH_FILE);
    gcsUtils.delete(REPORT_FILE);
    // Add a folder that is alphabetically before the desired folder and fill it with nonsense data.
    // It should NOT be picked up.
    BlobId ghostrydeFileWithPrefixBefore =
        BlobId.of("bucket", JOB_PREFIX + "-job-nama/tld_2010-10-17_full_S1_R0.xml.ghostryde");
    BlobId lengthFileWithPrefixBefore =
        BlobId.of("bucket", JOB_PREFIX + "-job-nama/tld_2010-10-17_full_S1_R0.xml.length");
    BlobId reportFileWithPrefixBefore =
        BlobId.of(
            "bucket", JOB_PREFIX + "-job-nama/tld_2010-10-17_full_S1_R0-report.xml.ghostryde");
    gcsUtils.createFromBytes(ghostrydeFileWithPrefixBefore, "foo".getBytes(UTF_8));
    gcsUtils.createFromBytes(lengthFileWithPrefixBefore, "bar".getBytes(UTF_8));
    gcsUtils.createFromBytes(reportFileWithPrefixBefore, "baz".getBytes(UTF_8));
    action.runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    // Assert that both files are written to SFTP and GCS, and that the contents are identical.
    String rydeFilename = "tld_2010-10-17_full_S1_R0.ryde";
    String rydeGcsFilename = JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.ryde";
    String sigFilename = "tld_2010-10-17_full_S1_R0.sig";
    String sigGcsFilename = JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R0.sig";
    assertThat(folder.list()).asList().containsExactly(rydeFilename, sigFilename);
    assertThat(gcsUtils.readBytesFrom(BlobId.of("bucket", rydeGcsFilename)))
        .isEqualTo(Files.toByteArray(new File(folder, rydeFilename)));
    assertThat(gcsUtils.readBytesFrom(BlobId.of("bucket", sigGcsFilename)))
        .isEqualTo(Files.toByteArray(new File(folder, sigFilename)));
  }

  @Test
  void testRunWithLock_resend() throws Exception {
    tm().transact(() -> RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 1));
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistSimpleResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    BlobId ghostrydeR1FileWithPrefix =
        BlobId.of("bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R1.xml.ghostryde");
    BlobId lengthR1FileWithPrefix =
        BlobId.of("bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R1.xml.length");
    BlobId reportR1FileWithPrefix =
        BlobId.of(
            "bucket", JOB_PREFIX + "-job-name/tld_2010-10-17_full_S1_R1-report.xml.ghostryde");
    gcsUtils.createFromBytes(
        ghostrydeR1FileWithPrefix, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    gcsUtils.createFromBytes(
        lengthR1FileWithPrefix, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    gcsUtils.createFromBytes(
        reportR1FileWithPrefix, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    createAction(uploadUrl).runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    assertThat(folder.list())
        .asList()
        .containsExactly("tld_2010-10-17_full_S1_R1.ryde", "tld_2010-10-17_full_S1_R1.sig");
  }

  @Test
  void testRunWithLock_producesValidSignature() throws Exception {
    assumeTrue(hasCommand(GPG_BINARY + " --version"));
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    createAction(uploadUrl).runWithLock(uploadCursor);
    // Only verify signature for SFTP versions, since we check elsewhere that the GCS files are
    // identical to the ones sent over SFTP.
    Process pid =
        gpg.exec(
            GPG_BINARY,
            "--verify",
            new File(folder, "tld_2010-10-17_full_S1_R0.sig").toString(),
            new File(folder, "tld_2010-10-17_full_S1_R0.ryde").toString());
    String stderr = slurp(pid.getErrorStream());
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertThat(stderr).contains("Good signature");
    assertThat(stderr).contains("rde-unittest@registry.test");
  }

  @Test
  void testRunWithLock_nonexistentCursor_throws204() throws Exception {
    int port = sftpd.serve("user", "password", folder);
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    RdeUploadAction action = createAction(uploadUrl);
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeStagingAction for TLD tld to send 2010-10-17T00:00:00.000Z upload; last"
                + " RDE staging completion was before 1970-01-01T00:00:00.000Z");
    cloudTasksHelper.assertNoTasksEnqueued("rde-upload");
    assertThat(folder.list()).isEmpty();
  }

  @Test
  void testRunWithLock_stagingNotFinished_throws204() {
    URI url = URI.create("sftp://user:password@localhost:32323/");
    DateTime stagingCursor = DateTime.parse("2010-10-17TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> createAction(url).runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeStagingAction for TLD tld to send 2010-10-17T00:00:00.000Z upload; "
                + "last RDE staging completion was before 2010-10-17T00:00:00.000Z");
  }

  @Test
  void testRunWithLock_sftpCooldownNotPassed_throws204() {
    RdeUploadAction action = createAction(URI.create("sftp://user:password@localhost:32323/"));
    action.sftpCooldown = standardHours(2);
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    DateTime sftpCursor = uploadCursor.minusMinutes(97); // Within the 2-hour cooldown period.
    persistResource(Cursor.createScoped(RDE_STAGING, stagingCursor, Tld.get("tld")));
    persistResource(Cursor.createScoped(RDE_UPLOAD_SFTP, sftpCursor, Tld.get("tld")));
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on 120 minute SFTP cooldown for TLD tld to send 2010-10-17T00:00:00.000Z"
                + " upload; last upload attempt was at 2010-10-16T22:23:00.000Z (97 minutes"
                + " ago)");
  }

  private static String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
