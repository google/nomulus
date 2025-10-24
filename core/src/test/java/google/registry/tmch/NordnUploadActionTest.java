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
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.VerifyException;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.RequestParameters;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.FakeUrlConnectionService;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import google.registry.util.Retrier;
import google.registry.util.UrlConnectionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link NordnUploadAction}. */
class NordnUploadActionTest {

  private static final String CLAIMS_CSV =
      """
      1,2010-05-04T10:11:12.000Z,2
      roid,domain-name,notice-id,registrar-id,registration-datetime,ack-datetime,application-datetime
      6-TLD,claims-landrush2.tld,landrush2tcn,88888,2010-05-03T10:11:12.000Z,2010-05-03T08:11:12.000Z
      8-TLD,claims-landrush1.tld,landrush1tcn,99999,2010-05-04T10:11:12.000Z,2010-05-04T09:11:12.000Z
      """;

  private static final String SUNRISE_CSV =
      """
      1,2010-05-04T10:11:12.000Z,2
      roid,domain-name,SMD-id,registrar-id,registration-datetime,application-datetime
      2-TLD,sunrise2.tld,new-smdid,88888,2010-05-01T10:11:12.000Z
      4-TLD,sunrise1.tld,my-smdid,99999,2010-05-02T10:11:12.000Z
      """;

  private static final String LOCATION_URL = "http://trololol";

  private final FakeClock clock = new FakeClock(DateTime.parse("2010-05-01T10:11:12Z"));
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);
  private final CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

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
    when(httpUrlConnection.getErrorStream())
        .thenReturn(new ByteArrayInputStream("Failure".getBytes(UTF_8)));
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_ACCEPTED);
    when(httpUrlConnection.getHeaderField(LOCATION)).thenReturn("http://trololol");
    when(httpUrlConnection.getOutputStream()).thenReturn(connectionOutputStream);
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setIanaIdentifier(99999L).build());
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setIanaIdentifier(88888L).build());
    createTld("tld");
    persistResource(Tld.get("tld").asBuilder().setLordnUsername("lolcat").build());
    persistSunriseModeDomain();
    clock.advanceBy(Duration.standardDays(1));
    persistClaimsModeDomain();
    action.clock = clock;
    action.cloudTasksUtils = cloudTasksUtils;
    action.urlConnectionService = urlConnectionService;
    action.lordnRequestInitializer = lordnRequestInitializer;
    action.phase = "claims";
    action.tld = "tld";
    action.tmchMarksdbUrl = "http://127.0.0.1";
    action.random = new SecureRandom();
    action.retrier = new Retrier(new FakeSleeper(clock), 3);
  }

  @Test
  void testSuccess_noPassword_doesntSendAuthorizationHeader() {
    action.lordnRequestInitializer = new LordnRequestInitializer(Optional.empty());
    action.run();
    verify(httpUrlConnection, times(0)).setRequestProperty(eq(AUTHORIZATION), anyString());
  }

  @Test
  void testSuccess_nothingScheduled() {
    persistResource(
        loadByKey(
                ForeignKeyUtils.loadKey(Domain.class, "claims-landrush1.tld", clock.nowUtc()).get())
            .asBuilder()
            .setLordnPhase(LordnPhase.NONE)
            .build());
    persistResource(
        loadByKey(
                ForeignKeyUtils.loadKey(Domain.class, "claims-landrush2.tld", clock.nowUtc()).get())
            .asBuilder()
            .setLordnPhase(LordnPhase.NONE)
            .build());
    action.run();
    verifyNoInteractions(httpUrlConnection);
    cloudTasksHelper.assertNoTasksEnqueued(NordnVerifyAction.QUEUE);
  }

  @Test
  void testSuccess_claimsMode() throws Exception {
    testRun("claims", "claims-landrush1.tld", "claims-landrush2.tld", CLAIMS_CSV);
  }

  @Test
  void testSuccess_sunriseMode() throws Exception {
    testRun("sunrise", "sunrise1.tld", "sunrise2.tld", SUNRISE_CSV);
  }

  @Test
  void testSuccess_noResponseContent_stillWorksNormally() throws Exception {
    // Returning null only affects logging.
    when(httpUrlConnection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {}));
    testRun("claims", "claims-landrush1.tld", "claims-landrush2.tld", CLAIMS_CSV);
  }

  @Test
  void testFailure_nullRegistryUser() {
    persistResource(Tld.get("tld").asBuilder().setLordnUsername(null).build());
    VerifyException thrown = assertThrows(VerifyException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("lordnUsername is not set for tld.");
  }

  @Test
  void testFailure_errorResponseCode() throws Exception {
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_INTERNAL_SERVER_ERROR);
    assertThrows(UrlConnectionException.class, action::run);
  }

  @Test
  void testFailure_noLocationHeaderInResponse() throws Exception {
    when(httpUrlConnection.getHeaderField(LOCATION)).thenReturn(null);
    assertThrows(UrlConnectionException.class, action::run);
  }

  private void persistClaimsModeDomain() {
    persistResource(
        newDomain("claims-landrush2.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.nowUtc())
            .setCreationRegistrarId("NewRegistrar")
            .setLaunchNotice(
                LaunchNotice.create("landrush2tcn", null, null, clock.nowUtc().minusHours(2)))
            .setLordnPhase(LordnPhase.CLAIMS)
            .build());
    clock.advanceBy(Duration.standardDays(1));
    persistResource(
        newDomain("claims-landrush1.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.nowUtc())
            .setLaunchNotice(
                LaunchNotice.create("landrush1tcn", null, null, clock.nowUtc().minusHours(1)))
            .setLordnPhase(LordnPhase.CLAIMS)
            .build());
  }

  private void persistSunriseModeDomain() {
    persistResource(
        newDomain("sunrise2.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.nowUtc())
            .setCreationRegistrarId("NewRegistrar")
            .setSmdId("new-smdid")
            .setLordnPhase(LordnPhase.SUNRISE)
            .build());
    clock.advanceBy(Duration.standardDays(1));
    persistResource(
        newDomain("sunrise1.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.nowUtc())
            .setSmdId("my-smdid")
            .setLordnPhase(LordnPhase.SUNRISE)
            .build());
  }

  private void verifyColumnCleared(String domainName) {
    VKey<Domain> domainKey =
        ForeignKeyUtils.loadKey(Domain.class, domainName, clock.nowUtc()).get();
    Domain domain = loadByKey(domainKey);
    assertThat(domain.getLordnPhase()).isEqualTo(LordnPhase.NONE);
  }

  @SuppressWarnings("DirectInvocationOnMock")
  private void testRun(String phase, String domain1, String domain2, String csv) throws Exception {
    action.phase = phase;
    action.run();
    verify(httpUrlConnection)
        .setRequestProperty(
            AUTHORIZATION, "Basic bG9sY2F0OmF0dGFjaw=="); // echo -n lolcat:attack | base64
    verify(httpUrlConnection)
        .setRequestProperty(eq(CONTENT_TYPE), startsWith("multipart/form-data; boundary="));
    verify(httpUrlConnection).setRequestMethod("POST");
    assertThat(httpUrlConnection.getURL())
        .isEqualTo(new URL("http://127.0.0.1/LORDN/tld/" + phase));
    assertThat(connectionOutputStream.toString(UTF_8)).contains(csv);
    verifyColumnCleared(domain1);
    verifyColumnCleared(domain2);
    cloudTasksHelper.assertTasksEnqueued(
        NordnVerifyAction.QUEUE,
        new TaskMatcher()
            .path(NordnVerifyAction.PATH)
            .param(NordnVerifyAction.NORDN_URL_PARAM, LOCATION_URL)
            .param(RequestParameters.PARAM_TLD, "tld")
            .header(CONTENT_TYPE, FORM_DATA.toString()));
  }
}
