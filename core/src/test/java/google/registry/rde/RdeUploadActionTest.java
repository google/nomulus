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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD_SFTP;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static google.registry.testing.GcsTestingUtils.writeGcsFile;
import static google.registry.testing.SystemInfo.hasCommand;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
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
import google.registry.model.registry.Registry;
import google.registry.rde.JSchSshSession.JSchSshSessionFactory;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.RequestParameters;
import google.registry.testing.AppEngineRule;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.GpgSystemCommandRule;
import google.registry.testing.Lazies;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.sftp.SftpServerRule;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.stubbing.OngoingStubbing;

/** Unit tests for {@link RdeUploadAction}. */
@RunWith(JUnit4.class)
public class RdeUploadActionTest {

  private static final int BUFFER_SIZE = 64 * 1024;
  private static final ByteSource REPORT_XML = RdeTestData.loadBytes("report.xml");
  private static final ByteSource DEPOSIT_XML = RdeTestData.loadBytes("deposit_full.xml");

  private static final GcsFilename GHOSTRYDE_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R0.xml.ghostryde");
  private static final GcsFilename LENGTH_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R0.xml.length");
  private static final GcsFilename REPORT_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R0-report.xml.ghostryde");

  private static final GcsFilename GHOSTRYDE_R1_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R1.xml.ghostryde");
  private static final GcsFilename LENGTH_R1_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R1.xml.length");
  private static final GcsFilename REPORT_R1_FILE =
      new GcsFilename("bucket", "tld_2010-10-17_full_S1_R1-report.xml.ghostryde");

  @Rule
  public final SftpServerRule sftpd = new SftpServerRule();

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Rule
  public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @Rule
  public final GpgSystemCommandRule gpg = new GpgSystemCommandRule(
      RdeTestData.loadBytes("pgp-public-keyring.asc"),
      RdeTestData.loadBytes("pgp-private-keyring-escrow.asc"));

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();


  private final FakeResponse response = new FakeResponse();
  private final EscrowTaskRunner runner = mock(EscrowTaskRunner.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2010-10-17TZ"));

  private RdeUploadAction createAction(URI uploadUrl) {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      RdeUploadAction action = new RdeUploadAction();
      action.clock = clock;
      action.gcsUtils = new GcsUtils(gcsService, BUFFER_SIZE);
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
      action.reportQueue = QueueFactory.getQueue("rde-report");
      action.runner = runner;
      action.taskQueueUtils = new TaskQueueUtils(new Retrier(null, 1));
      action.retrier = new Retrier(new FakeSleeper(clock), 3);
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

  private GcsService gcsService;

  @Before
  public void before() throws Exception {
    // Force "development" mode so we don't try to really connect to GCS.
    SystemProperty.environment.set(SystemProperty.Environment.Value.Development);
    gcsService = GcsServiceFactory.createGcsService();

    createTld("tld");
    PGPPublicKey encryptKey = new FakeKeyringModule().get().getRdeStagingEncryptionKey();
    writeGcsFile(gcsService, GHOSTRYDE_FILE, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    writeGcsFile(gcsService, GHOSTRYDE_R1_FILE, Ghostryde.encode(DEPOSIT_XML.read(), encryptKey));
    writeGcsFile(gcsService, LENGTH_FILE, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    writeGcsFile(gcsService, LENGTH_R1_FILE, Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    writeGcsFile(gcsService, REPORT_FILE, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    writeGcsFile(gcsService, REPORT_R1_FILE, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm()
        .transact(
            () -> {
              RdeRevision.saveRevision("lol", DateTime.parse("2010-10-17TZ"), FULL, 0);
              RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 0);
            });
  }

  @Test
  public void testSocketConnection() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    try (Socket socket = new Socket("localhost", port)) {
      assertThat(socket.isConnected()).isTrue();
    }
  }

  @Test
  public void testRun() {
    createTld("lol");
    RdeUploadAction action = createAction(null);
    action.tld = "lol";
    action.run();
    verify(runner).lockRunAndRollForward(
        action, Registry.get("lol"), standardSeconds(23), CursorType.RDE_UPLOAD, standardDays(1));
    assertTasksEnqueued("rde-report", new TaskMatcher()
        .url(RdeReportAction.PATH)
        .param(RequestParameters.PARAM_TLD, "lol"));
    verifyNoMoreInteractions(runner);
  }

  @Test
  public void testRunWithLock_succeedsOnThirdTry() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    action.lazyJsch = Lazies.of(createThrowingJSchSpy(action.lazyJsch.get(), 2));
    action.runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    assertNoTasksEnqueued("rde-upload");
    assertThat(folder.getRoot().list()).asList()
        .containsExactly(
            "tld_2010-10-17_full_S1_R0.ryde",
            "tld_2010-10-17_full_S1_R0.sig");
  }

  @Test
  public void testRunWithLock_failsAfterThreeAttempts() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    RdeUploadAction action = createAction(uploadUrl);
    action.lazyJsch = Lazies.of(createThrowingJSchSpy(action.lazyJsch.get(), 3));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown).hasMessageThat().contains("The crow flies in square circles.");
  }

  @Test
  public void testRunWithLock_copiesOnGcs() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    createAction(uploadUrl).runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    assertNoTasksEnqueued("rde-upload");
    // Assert that both files are written to SFTP and GCS, and that the contents are identical.
    String rydeFilename = "tld_2010-10-17_full_S1_R0.ryde";
    String sigFilename = "tld_2010-10-17_full_S1_R0.sig";
    assertThat(folder.getRoot().list()).asList().containsExactly(rydeFilename, sigFilename);
    assertThat(readGcsFile(gcsService, new GcsFilename("bucket", rydeFilename)))
        .isEqualTo(Files.toByteArray(new File(folder.getRoot(), rydeFilename)));
    assertThat(readGcsFile(gcsService, new GcsFilename("bucket", sigFilename)))
        .isEqualTo(Files.toByteArray(new File(folder.getRoot(), sigFilename)));
  }

  @Test
  public void testRunWithLock_resend() throws Exception {
    tm().transact(() -> RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 1));
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistSimpleResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    createAction(uploadUrl).runWithLock(uploadCursor);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK tld 2010-10-17T00:00:00.000Z\n");
    assertNoTasksEnqueued("rde-upload");
    assertThat(folder.getRoot().list()).asList()
        .containsExactly(
            "tld_2010-10-17_full_S1_R1.ryde",
            "tld_2010-10-17_full_S1_R1.sig");
  }

  @Test
  public void testRunWithLock_producesValidSignature() throws Exception {
    assumeTrue(hasCommand("gpg --version"));
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@localhost:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    createAction(uploadUrl).runWithLock(uploadCursor);
    // Only verify signature for SFTP versions, since we check elsewhere that the GCS files are
    // identical to the ones sent over SFTP.
    Process pid = gpg.exec("gpg", "--verify",
        new File(folder.getRoot(), "tld_2010-10-17_full_S1_R0.sig").toString(),
        new File(folder.getRoot(), "tld_2010-10-17_full_S1_R0.ryde").toString());
    String stderr = slurp(pid.getErrorStream());
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertThat(stderr).contains("Good signature");
    assertThat(stderr).contains("rde-unittest@registry.test");
  }

  @Test
  public void testRunWithLock_stagingNotFinished_throws204() {
    URI url = URI.create("sftp://user:password@localhost:32323/");
    DateTime stagingCursor = DateTime.parse("2010-10-17TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> createAction(url).runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeStagingAction for TLD tld to send 2010-10-17T00:00:00.000Z upload; "
                + "last RDE staging completion was at 2010-10-17T00:00:00.000Z");
  }

  @Test
  public void testRunWithLock_sftpCooldownNotPassed_throws204() {
    RdeUploadAction action = createAction(URI.create("sftp://user:password@localhost:32323/"));
    action.sftpCooldown = standardHours(2);
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    DateTime sftpCursor = uploadCursor.minusMinutes(97); // Within the 2 hour cooldown period.
    persistResource(Cursor.create(RDE_STAGING, stagingCursor, Registry.get("tld")));
    persistResource(Cursor.create(RDE_UPLOAD_SFTP, sftpCursor, Registry.get("tld")));
    NoContentException thrown =
        assertThrows(NoContentException.class, () -> action.runWithLock(uploadCursor));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on 120 minute SFTP cooldown for TLD tld to send 2010-10-17T00:00:00.000Z "
                + "upload; last upload attempt was at 2010-10-16T22:23:00.000Z (97 minutes ago)");
  }

  private String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
