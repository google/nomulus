// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.rde.RdeMode.FULL;
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
import static org.joda.time.Duration.standardSeconds;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.googlecode.objectify.VoidWork;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.Keyring;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registry.Registry;
import google.registry.rde.JSchSshSession.JSchSshSessionFactory;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.RequestParameters;
import google.registry.testing.AppEngineRule;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.GpgSystemCommandRule;
import google.registry.testing.IoSpyRule;
import google.registry.testing.Providers;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.sftp.SftpServerRule;
import google.registry.util.Retrier;
import google.registry.util.TaskEnqueuer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeUploadAction}. */
@RunWith(MockitoJUnitRunner.class)
public class RdeUploadActionTest {

  private static final int BUFFER_SIZE = 64 * 1024;
  private static final ByteSource REPORT_XML = RdeTestData.get("report.xml");
  private static final ByteSource DEPOSIT_XML = RdeTestData.get("deposit_full.xml");  // 2010-10-17

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
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final SftpServerRule sftpd = new SftpServerRule();

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Rule
  public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @Rule
  public final GpgSystemCommandRule gpg = new GpgSystemCommandRule(
      RdeTestData.get("pgp-public-keyring.asc"),
      RdeTestData.get("pgp-private-keyring-escrow.asc"));

  @Rule
  public final IoSpyRule ioSpy = new IoSpyRule()
      .checkClosedOnlyOnce()
      .checkCharIoMaxCalls(10);

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  private final FakeResponse response = new FakeResponse();
  private final EscrowTaskRunner runner = mock(EscrowTaskRunner.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2010-10-17TZ"));
  private final GcsService gcsService = GcsServiceFactory.createGcsService();

  private final RydeTarOutputStreamFactory tarFactory =
      new RydeTarOutputStreamFactory() {
        @Override
        public RydeTarOutputStream create(
            OutputStream os, long size, DateTime modified, String filename) {
          return ioSpy.register(super.create(os, size, modified, filename));
        }};

  private final RydePgpFileOutputStreamFactory literalFactory =
      new RydePgpFileOutputStreamFactory(Providers.of(BUFFER_SIZE)) {
        @Override
        public RydePgpFileOutputStream create(OutputStream os, DateTime modified, String filename) {
          return ioSpy.register(super.create(os, modified, filename));
        }};

  private final RydePgpEncryptionOutputStreamFactory encryptFactory =
      new RydePgpEncryptionOutputStreamFactory(Providers.of(BUFFER_SIZE)) {
        @Override
        public RydePgpEncryptionOutputStream create(OutputStream os, PGPPublicKey publicKey) {
          return ioSpy.register(super.create(os, publicKey));
        }};

  private final RydePgpCompressionOutputStreamFactory compressFactory =
      new RydePgpCompressionOutputStreamFactory(Providers.of(BUFFER_SIZE)) {
        @Override
        public RydePgpCompressionOutputStream create(OutputStream os) {
          return ioSpy.register(super.create(os));
        }};

  private final RydePgpSigningOutputStreamFactory signFactory =
      new RydePgpSigningOutputStreamFactory() {
        @Override
        public RydePgpSigningOutputStream create(OutputStream os, PGPKeyPair signingKey) {
          return ioSpy.register(super.create(os, signingKey));
        }};

  private RdeUploadAction createAction(URI uploadUrl) {
    try (Keyring keyring = new RdeKeyringModule().get()) {
      RdeUploadAction action = new RdeUploadAction();
      action.clock = clock;
      action.gcsUtils = new GcsUtils(gcsService, BUFFER_SIZE);
      action.ghostryde = new Ghostryde(BUFFER_SIZE);
      action.jsch =
          JSchModule.provideJSch(
              "user@ignored",
              keyring.getRdeSshClientPrivateKey(), keyring.getRdeSshClientPublicKey());
      action.jschSshSessionFactory = new JSchSshSessionFactory(standardSeconds(3));
      action.response = response;
      action.pgpCompressionFactory = compressFactory;
      action.pgpEncryptionFactory = encryptFactory;
      action.pgpFileFactory = literalFactory;
      action.pgpSigningFactory = signFactory;
      action.tarFactory = tarFactory;
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
      action.taskEnqueuer = new TaskEnqueuer(new Retrier(null, 1));
      return action;
    }
  }

  @Before
  public void before() throws Exception {
    createTld("tld");
    PGPPublicKey encryptKey = new RdeKeyringModule().get().getRdeStagingEncryptionKey();
    writeGcsFile(gcsService, GHOSTRYDE_FILE,
        Ghostryde.encode(DEPOSIT_XML.read(), encryptKey, "lobster.xml", clock.nowUtc()));
    writeGcsFile(gcsService, GHOSTRYDE_R1_FILE,
        Ghostryde.encode(DEPOSIT_XML.read(), encryptKey, "lobster.xml", clock.nowUtc()));
    writeGcsFile(gcsService, LENGTH_FILE,
        Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    writeGcsFile(gcsService, LENGTH_R1_FILE,
        Long.toString(DEPOSIT_XML.size()).getBytes(UTF_8));
    writeGcsFile(gcsService, REPORT_FILE,
        Ghostryde.encode(REPORT_XML.read(), encryptKey, "dieform.xml", clock.nowUtc()));
    writeGcsFile(gcsService, REPORT_R1_FILE,
        Ghostryde.encode(REPORT_XML.read(), encryptKey, "dieform.xml", clock.nowUtc()));
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        RdeRevision.saveRevision("lol", DateTime.parse("2010-10-17TZ"), FULL, 0);
        RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 0);
      }});
  }

  @Test
  public void testSocketConnection() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    try (Socket socket = new Socket("::1", port)) {
      assertThat(socket.isConnected()).isTrue();
    }
  }

  @Test
  public void testRun() throws Exception {
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
  public void testRunWithLock() throws Exception {
    // XXX: For any port other than 22, JSch will reformat the hostname IPv6 style which causes
    //      known host matching to fail.
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@127.0.0.1:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, stagingCursor, Registry.get("tld")));
    createAction(uploadUrl).runWithLock(uploadCursor);
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
  public void testRunWithLock_copiesOnGcs() throws Exception {
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@127.0.0.1:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, stagingCursor, Registry.get("tld")));
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
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        RdeRevision.saveRevision("tld", DateTime.parse("2010-10-17TZ"), FULL, 1);
      }});
    int port = sftpd.serve("user", "password", folder.getRoot());
    URI uploadUrl = URI.create(String.format("sftp://user:password@127.0.0.1:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistSimpleResource(
        Cursor.create(CursorType.RDE_STAGING, stagingCursor, Registry.get("tld")));
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
    URI uploadUrl = URI.create(String.format("sftp://user:password@127.0.0.1:%d/", port));
    DateTime stagingCursor = DateTime.parse("2010-10-18TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, stagingCursor, Registry.get("tld")));
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
  public void testRunWithLock_stagingNotFinished_throws503() throws Exception {
    DateTime stagingCursor = DateTime.parse("2010-10-17TZ");
    DateTime uploadCursor = DateTime.parse("2010-10-17TZ");
    persistResource(
        Cursor.create(CursorType.RDE_STAGING, stagingCursor, Registry.get("tld")));
    thrown.expect(ServiceUnavailableException.class, "Waiting for RdeStagingAction to complete");
    createAction(null).runWithLock(uploadCursor);
  }

  private String slurp(InputStream is) throws FileNotFoundException, IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
