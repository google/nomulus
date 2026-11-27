// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setJpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setReplicaJpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusDays;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.net.HostAndPort;
import com.google.common.testing.TestLogHandler;
import com.google.gson.Gson;
import google.registry.bsa.api.BsaCredential;
import google.registry.bsa.persistence.BsaTld;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.ReservedList;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManager.ThrowingRunnable;
import google.registry.request.UrlConnectionService;
import google.registry.server.Route;
import google.registry.server.TestServer;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link UploadBsaUnavailableDomainsAction}. */
@ExtendWith(MockitoExtension.class)
public class UploadBsaUnavailableDomainsActionTest {

  private static final String BUCKET = "domain-registry-bsa";

  private static final String API_URL = "https://upload.test/bsa";

  private final FakeClock clock = new FakeClock(Instant.parse("2024-02-02T02:02:02Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private UploadBsaUnavailableDomainsAction action;

  @Mock UrlConnectionService connectionService;

  @Mock BsaCredential bsaCredential;

  @Mock BsaEmailSender emailSender;

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final FakeResponse response = new FakeResponse();

  @BeforeEach
  void beforeEach() {
    ReservedList reservedList =
        persistReservedList(
            "tld-reserved_list",
            "tine,FULLY_BLOCKED",
            "flagrant,NAME_COLLISION",
            "jimmy,RESERVED_FOR_SPECIFIC_USE");
    createTld("tld");
    tm().transact(() -> tm().put(new BsaTld("tld", clock.now())));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(reservedList)
            .setBsaEnrollStartTime(Optional.of(START_INSTANT))
            .setTldType(TldType.REAL)
            .build());
    action =
        new UploadBsaUnavailableDomainsAction(
            clock, bsaCredential, gcsUtils, emailSender, BUCKET, API_URL, response);
  }

  @Test
  void calculatesEntriesCorrectly() throws Exception {
    persistActiveDomain("foobar.tld");
    persistActiveDomain("ace.tld");
    persistDeletedDomain("not-blocked.tld", minusDays(clock.now(), 1));
    action.run();
    BlobId existingFile =
        BlobId.of(BUCKET, String.format("unavailable_domains_%s.txt", clock.now()));
    String blockList = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    assertThat(blockList).isEqualTo("ace.tld\nflagrant.tld\nfoobar.tld\njimmy.tld\ntine.tld\n");
    assertThat(blockList).doesNotContain("not-blocked.tld");

    // This test currently fails in the upload-to-bsa step.
    verify(emailSender, times(1))
        .sendNotification("BSA daily upload completed with errors", "Please see logs for details.");
  }

  @Test
  void uploadToBsaTest() throws Exception {
    TestLogHandler logHandler = new TestLogHandler();
    Logger loggerToIntercept =
        Logger.getLogger(UploadBsaUnavailableDomainsAction.class.getCanonicalName());
    loggerToIntercept.addHandler(logHandler);

    persistActiveDomain("foobar.tld");
    persistActiveDomain("ace.tld");
    persistDeletedDomain("not-blocked.tld", minusDays(clock.now(), 1));

    var testServer = startTestServer();
    action.apiUrl = testServer.getUrl("/upload").toURI().toString();
    try {
      action.run();
    } finally {
      testServer.stop();
    }
    String dataSent = "ace.tld\nflagrant.tld\nfoobar.tld\njimmy.tld\ntine.tld\n";
    String checkSum = Hashing.sha512().hashString(dataSent, UTF_8).toString();
    String expectedResponse =
        "Received response with code 200 from server: "
            + String.format("Checksum: [%s]\n%s\n", checkSum, dataSent);
    assertAboutLogs().that(logHandler).hasLogAtLevelWithMessage(Level.INFO, expectedResponse);
    verify(emailSender, times(1)).sendNotification("BSA daily upload completed successfully", "");
    loggerToIntercept.removeHandler(logHandler);
  }

  @Test
  void uploadToBsa_non2xxResponse_failsAndNotifiesErrors() throws Exception {
    TestLogHandler logHandler = new TestLogHandler();
    Logger loggerToIntercept =
        Logger.getLogger(UploadBsaUnavailableDomainsAction.class.getCanonicalName());
    loggerToIntercept.addHandler(logHandler);

    persistActiveDomain("foobar.tld");

    var testServer = startTestServer();
    action.apiUrl = testServer.getUrl("/upload-error").toURI().toString();
    try {
      action.run();
    } finally {
      testServer.stop();
      loggerToIntercept.removeHandler(logHandler);
    }

    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("500");
    assertThat(response.getPayload()).contains("upstream failure");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE,
            "Failed to upload unavailable domains to BSA: status code 500 from server: upstream"
                + " failure");
    verify(emailSender, times(1))
        .sendNotification("BSA daily upload completed with errors", "Please see logs for details.");
    verify(emailSender, never()).sendNotification("BSA daily upload completed successfully", "");
  }

  @Test
  void reservedListLoad_doesNotNestInsideReplicaTransaction() throws Exception {
    ReservedList uncachedReservedList =
        persistReservedList("tld-uncached-reserved-list", "freshlabel,FULLY_BLOCKED");
    persistResource(Tld.get("tld").asBuilder().setReservedLists(uncachedReservedList).build());

    JpaTransactionManager originalPrimary = tm();
    JpaTransactionManager originalReplica = replicaTm();
    JpaTransactionManager spiedPrimary =
        mock(JpaTransactionManager.class, delegatesTo(originalPrimary));
    JpaTransactionManager spiedReplica =
        mock(JpaTransactionManager.class, delegatesTo(originalReplica));
    AtomicBoolean nestedInReplica = new AtomicBoolean(false);
    AtomicBoolean primaryReTransactCalled = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              primaryReTransactCalled.set(true);
              if (originalReplica.inTransaction()) {
                nestedInReplica.set(true);
              }
              return originalPrimary.reTransact(invocation.<Callable<?>>getArgument(0));
            })
        .when(spiedPrimary)
        .reTransact(any(Callable.class));

    setJpaTm(() -> spiedPrimary);
    setReplicaJpaTm(() -> spiedReplica);
    try {
      action.run();
    } finally {
      setJpaTm(() -> originalPrimary);
      setReplicaJpaTm(() -> originalReplica);
    }

    assertThat(primaryReTransactCalled.get()).isTrue();
    assertThat(nestedInReplica.get()).isFalse();
    verify(spiedReplica, never()).transact(any(ThrowingRunnable.class));
    BlobId existingFile = BlobId.of(BUCKET, "unavailable_domains_2024-02-02T02:02:02Z.txt");
    String blockList = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    assertThat(blockList).isEqualTo("freshlabel.tld\n");
  }

  @Test
  void noActivatedBsaTlds_sendsNoDomainsNotification() {
    tm().transact(() -> tm().delete(BsaTld.createVkey("tld")));
    action.run();
    verify(emailSender, times(1))
        .sendNotification(
            "BSA daily upload found no domains to upload",
            "This is unexpected. Please investigate.");
  }

  private TestServer startTestServer() throws Exception {
    TestServer testServer =
        new TestServer(
            HostAndPort.fromParts(InetAddress.getLocalHost().getHostAddress(), 0),
            ImmutableMap.of(),
            ImmutableList.of(
                Route.route("/upload", Servlet.class),
                Route.route("/upload-error", ErrorServlet.class)));
    testServer.start();
    newSingleThreadExecutor()
        .execute(
            () -> {
              try {
                while (true) {
                  testServer.process();
                }
              } catch (InterruptedException e) {
                // Expected
              }
            });
    return testServer;
  }

  @MultipartConfig(
      location = "", // Directory for storing uploaded files. Use default when blank
      maxFileSize = 10485760L, // 10MB
      maxRequestSize = 20971520L, // 20MB
      fileSizeThreshold = 1048576 // Save in memory if file size < 1MB
      )
  public static class Servlet extends HttpServlet {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      String checkSum = null;
      String content = null;
      try {
        for (Part part : req.getParts()) {
          switch (part.getName()) {
            case "zone" -> checkSum = readChecksum(part);
            case "file" -> content = readGzipped(part);
          }
        }
      } catch (Exception e) {
        logger.atInfo().withCause(e).log("");
      }
      int status = checkSum == null || content == null ? 400 : 200;
      resp.setStatus(status);
      resp.setContentType("text/plain");
      try (PrintWriter writer = resp.getWriter()) {
        writer.printf("Checksum: [%s]\n%s\n", checkSum, content);
      }
    }

    private String readChecksum(Part part) {
      try (InputStream is = part.getInputStream()) {
        return new Gson()
            .fromJson(new String(ByteStreams.toByteArray(is), UTF_8), Map.class)
            .getOrDefault("checkSum", "Not found")
            .toString();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private String readGzipped(Part part) {
      try (InputStream is = part.getInputStream();
          GZIPInputStream gis = new GZIPInputStream(is)) {
        return new String(ByteStreams.toByteArray(gis), UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class ErrorServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      resp.setStatus(SC_INTERNAL_SERVER_ERROR);
      resp.setContentType("text/plain");
      try (PrintWriter writer = resp.getWriter()) {
        writer.print("upstream failure");
      }
    }
  }
}
