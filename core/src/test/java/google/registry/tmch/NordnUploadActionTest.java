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

package google.registry.tmch;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.MediaType.FORM_DATA;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistDomainAndEnqueueLordn;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.taskqueue.LeaseOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.apphosting.api.DeadlineExceededException;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import google.registry.model.domain.Domain;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.tld.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.FakeUrlConnectionService;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import google.registry.util.UrlConnectionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link NordnUploadAction}. */
class NordnUploadActionTest {

  private static final String CLAIMS_CSV =
      "1,2010-05-01T10:11:12.000Z,1\n"
          + "roid,domain-name,notice-id,registrar-id,registration-datetime,ack-datetime,"
          + "application-datetime\n"
          + "2-TLD,claims-landrush1.tld,landrush1tcn,99999,2010-05-01T10:11:12.000Z,"
          + "1969-12-31T23:00:00.000Z\n";

  private static final String SUNRISE_CSV =
      "1,2010-05-01T10:11:12.000Z,1\n"
          + "roid,domain-name,SMD-id,registrar-id,registration-datetime,application-datetime\n"
          + "2-TLD,sunrise1.tld,my-smdid,99999,2010-05-01T10:11:12.000Z\n";

  private static final String LOCATION_URL = "http://trololol";

  private final FakeClock clock = new FakeClock(DateTime.parse("2010-05-01T10:11:12Z"));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withClock(clock).withTaskQueue().build();

  private final LordnRequestInitializer lordnRequestInitializer =
      new LordnRequestInitializer(Optional.of("attack"));
  private final NordnUploadAction action = new NordnUploadAction();

  private final HttpURLConnection httpUrlConnection = mock(HttpURLConnection.class);
  private final ByteArrayOutputStream connectionOutputStream = new ByteArrayOutputStream();
  private final FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(httpUrlConnection);

  @BeforeEach
  void beforeEach() throws Exception {
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream("Success".getBytes(UTF_8)));
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_ACCEPTED);
    when(httpUrlConnection.getHeaderField(LOCATION)).thenReturn("http://trololol");
    when(httpUrlConnection.getOutputStream()).thenReturn(connectionOutputStream);
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setIanaIdentifier(99999L).build());
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setLordnUsername("lolcat").build());
    action.clock = clock;
    action.urlConnectionService = urlConnectionService;
    action.lordnRequestInitializer = lordnRequestInitializer;
    action.phase = "claims";
    action.taskQueueUtils = new TaskQueueUtils(new Retrier(new FakeSleeper(clock), 3));
    action.tld = "tld";
    action.tmchMarksdbUrl = "http://127.0.0.1";
    action.random = new SecureRandom();
    action.retrier = new Retrier(new FakeSleeper(clock), 3);
  }

  @Test
  void test_convertTasksToCsv() {
    List<TaskHandle> tasks =
        ImmutableList.of(
            makeTaskHandle("task2", "example", "csvLine2", "lordn-sunrise"),
            makeTaskHandle("task1", "example", "csvLine1", "lordn-sunrise"),
            makeTaskHandle("task3", "example", "ending", "lordn-sunrise"));
    assertThat(NordnUploadAction.convertTasksToCsv(tasks, clock.nowUtc(), "col1,col2"))
        .isEqualTo("1,2010-05-01T10:11:12.000Z,3\ncol1,col2\ncsvLine1\ncsvLine2\nending\n");
  }

  @Test
  void test_convertTasksToCsv_dedupesDuplicates() {
    List<TaskHandle> tasks =
        ImmutableList.of(
            makeTaskHandle("task2", "example", "csvLine2", "lordn-sunrise"),
            makeTaskHandle("task1", "example", "csvLine1", "lordn-sunrise"),
            makeTaskHandle("task3", "example", "ending", "lordn-sunrise"),
            makeTaskHandle("task1", "example", "csvLine1", "lordn-sunrise"));
    assertThat(NordnUploadAction.convertTasksToCsv(tasks, clock.nowUtc(), "col1,col2"))
        .isEqualTo("1,2010-05-01T10:11:12.000Z,3\ncol1,col2\ncsvLine1\ncsvLine2\nending\n");
  }

  @Test
  void test_convertTasksToCsv_doesntFailOnEmptyTasks() {
    assertThat(NordnUploadAction.convertTasksToCsv(ImmutableList.of(), clock.nowUtc(), "col1,col2"))
        .isEqualTo("1,2010-05-01T10:11:12.000Z,0\ncol1,col2\n");
  }

  @Test
  void test_convertTasksToCsv_throwsNpeOnNullTasks() {
    assertThrows(
        NullPointerException.class,
        () -> NordnUploadAction.convertTasksToCsv(null, clock.nowUtc(), "header"));
  }

  @Test
  void test_loadAllTasks_retryLogic_thirdTrysTheCharm() {
    Queue queue = mock(Queue.class);
    TaskHandle task = new TaskHandle(TaskOptions.Builder.withTaskName("blah"), "blah");
    when(queue.leaseTasks(any(LeaseOptions.class)))
        .thenThrow(TransientFailureException.class)
        .thenThrow(DeadlineExceededException.class)
        .thenReturn(ImmutableList.of(task), ImmutableList.of());
    assertThat(action.loadAllTasks(queue, "tld")).containsExactly(task);
  }

  @Test
  void test_loadAllTasks_retryLogic_allFailures() {
    Queue queue = mock(Queue.class);
    when(queue.leaseTasks(any(LeaseOptions.class)))
        .thenThrow(new TransientFailureException("some transient error"));
    RuntimeException thrown =
        assertThrows(TransientFailureException.class, () -> action.loadAllTasks(queue, "tld"));
    assertThat(thrown).hasMessageThat().isEqualTo("some transient error");
  }

  @Test
  void testRun_claimsMode_appendsTldAndClaimsToRequestUrl() throws Exception {
    persistClaimsModeDomain();
    action.run();
    assertThat(httpUrlConnection.getURL()).isEqualTo(new URL("http://127.0.0.1/LORDN/tld/claims"));
  }

  @Test
  void testRun_sunriseMode_appendsTldAndClaimsToRequestUrl() throws Exception {
    persistSunriseModeDomain();
    action.run();
    assertThat(httpUrlConnection.getURL()).isEqualTo(new URL("http://127.0.0.1/LORDN/tld/sunrise"));
  }

  @Test
  void testRun_usesMultipartContentType() throws Exception {
    persistClaimsModeDomain();
    action.run();
    verify(httpUrlConnection)
        .setRequestProperty(eq(CONTENT_TYPE), startsWith("multipart/form-data; boundary="));
    verify(httpUrlConnection).setRequestMethod("POST");
  }

  @Test
  void testRun_hasPassword_setsAuthorizationHeader() {
    persistClaimsModeDomain();
    action.run();
    verify(httpUrlConnection)
        .setRequestProperty(
            AUTHORIZATION, "Basic bG9sY2F0OmF0dGFjaw=="); // echo -n lolcat:attack | base64
  }

  @Test
  void testRun_noPassword_doesntSendAuthorizationHeader() {
    action.lordnRequestInitializer = new LordnRequestInitializer(Optional.empty());
    persistClaimsModeDomain();
    action.run();
    verify(httpUrlConnection, times(0)).setRequestProperty(eq(AUTHORIZATION), anyString());
  }

  @Test
  void testRun_claimsMode_payloadMatchesClaimsCsv() {
    persistClaimsModeDomain();
    action.run();
    assertThat(connectionOutputStream.toString(UTF_8)).contains(CLAIMS_CSV);
  }

  @Test
  void testRun_claimsMode_verifyTaskGetsEnqueuedWithClaimsCsv() {
    persistClaimsModeDomain();
    action.run();
    assertTasksEnqueued(
        NordnVerifyAction.QUEUE,
        new TaskMatcher()
            .url(NordnVerifyAction.PATH)
            .header(NordnVerifyAction.URL_HEADER, LOCATION_URL)
            .header(CONTENT_TYPE, FORM_DATA.toString()));
  }

  @Test
  void testRun_sunriseMode_payloadMatchesSunriseCsv() {
    persistSunriseModeDomain();
    action.run();
    assertThat(connectionOutputStream.toString(UTF_8)).contains(SUNRISE_CSV);
  }

  @Test
  void test_noResponseContent_stillWorksNormally() throws Exception {
    // Returning null only affects logging.
    when(httpUrlConnection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {}));
    persistSunriseModeDomain();
    action.run();
    assertThat(connectionOutputStream.toString(UTF_8)).contains(SUNRISE_CSV);
  }

  @Test
  void testRun_sunriseMode_verifyTaskGetsEnqueuedWithSunriseCsv() {
    persistSunriseModeDomain();
    action.run();
    assertTasksEnqueued(
        NordnVerifyAction.QUEUE,
        new TaskMatcher()
            .url(NordnVerifyAction.PATH)
            .header(NordnVerifyAction.URL_HEADER, LOCATION_URL)
            .header(CONTENT_TYPE, FORM_DATA.toString()));
  }

  @Test
  void testFailure_nullRegistryUser() {
    persistClaimsModeDomain();
    persistResource(Registry.get("tld").asBuilder().setLordnUsername(null).build());
    VerifyException thrown = assertThrows(VerifyException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("lordnUsername is not set for tld.");
  }

  @Test
  void testFetchFailure() throws Exception {
    persistClaimsModeDomain();
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_INTERNAL_SERVER_ERROR);
    assertThrows(UrlConnectionException.class, action::run);
  }

  private static void persistClaimsModeDomain() {
    Domain domain = DatabaseHelper.newDomain("claims-landrush1.tld");
    persistDomainAndEnqueueLordn(
        domain
            .asBuilder()
            .setLaunchNotice(
                LaunchNotice.create(
                    "landrush1tcn", null, null, domain.getCreationTime().minusHours(1)))
            .build());
  }

  private void persistSunriseModeDomain() {
    action.phase = "sunrise";
    Domain domain = DatabaseHelper.newDomain("sunrise1.tld");
    persistDomainAndEnqueueLordn(domain.asBuilder().setSmdId("my-smdid").build());
  }

  private static TaskHandle makeTaskHandle(
      String taskName, String tag, String payload, String queue) {
    return new TaskHandle(
        TaskOptions.Builder.withPayload(payload).method(Method.PULL).tag(tag).taskName(taskName),
        queue);
  }
}
