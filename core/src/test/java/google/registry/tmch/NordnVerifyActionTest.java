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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.tld.Registry;
import google.registry.request.HttpException.ConflictException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeUrlConnectionService;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link NordnVerifyAction}. */
class NordnVerifyActionTest {

  private static final String LOG_ACCEPTED =
      "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
          + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,no-warnings,1\n"
          + "roid,result-code\n"
          + "SH8013-REP,2000";

  private static final String LOG_REJECTED =
      "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
          + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,rejected,no-warnings,1\n"
          + "roid,result-code\n"
          + "SH8013-REP,2001";

  private static final String LOG_WARNINGS =
      "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,warnings-present,3\n"
          + "roid,result-code\n"
          + "SH8013-REP,2001\n"
          + "lulz-roid,3609\n"
          + "sabokitty-roid,3610\n";

  private static final String LOG_ERRORS =
      "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,warnings-present,3\n"
          + "roid,result-code\n"
          + "SH8013-REP,2000\n"
          + "lulz-roid,4601\n"
          + "bogpog,4611\n";

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withTaskQueue().build();

  private final FakeResponse response = new FakeResponse();
  private final LordnRequestInitializer lordnRequestInitializer =
      new LordnRequestInitializer(Optional.of("attack"));
  private final NordnVerifyAction action = new NordnVerifyAction();

  private final HttpURLConnection httpUrlConnection = mock(HttpURLConnection.class);
  private final FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(httpUrlConnection);

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("gtld");
    persistResource(Registry.get("gtld").asBuilder().setLordnUsername("lolcat").build());
    action.tld = "gtld";
    action.urlConnectionService = urlConnectionService;
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_OK);
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(LOG_ACCEPTED.getBytes(UTF_8)));
    action.lordnRequestInitializer = lordnRequestInitializer;
    action.response = response;
    action.url = new URL("http://127.0.0.1/blobio");
  }

  @Test
  void testSuccess_sendHttpRequest_urlIsCorrect() throws Exception {
    action.run();
    assertThat(httpUrlConnection.getURL()).isEqualTo(new URL("http://127.0.0.1/blobio"));
  }

  @Test
  void testSuccess_hasLordnPassword_sendsAuthorizationHeader() {
    action.run();
    verify(httpUrlConnection)
        .setRequestProperty(
            AUTHORIZATION, "Basic bG9sY2F0OmF0dGFjaw=="); // echo -n lolcat:attack | base64
  }

  @Test
  void testSuccess_noLordnPassword_doesntSetAuthorizationHeader() {
    action.lordnRequestInitializer = new LordnRequestInitializer(Optional.empty());
    action.run();
    verify(httpUrlConnection, times(0)).setRequestProperty(eq(AUTHORIZATION), anyString());
  }

  @Test
  void successVerifyRejected() throws Exception {
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(LOG_REJECTED.getBytes(UTF_8)));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.getStatus()).isEqualTo(LordnLog.Status.REJECTED);
  }

  @Test
  void successVerifyWarnings() throws Exception {
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(LOG_WARNINGS.getBytes(UTF_8)));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.hasWarnings()).isTrue();
  }

  @Test
  void successVerifyErrors() throws Exception {
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(LOG_ERRORS.getBytes(UTF_8)));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.hasWarnings()).isTrue();
  }

  @Test
  void failureVerifyUnauthorized() throws Exception {
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_UNAUTHORIZED);
    assertThrows(Exception.class, action::run);
  }

  @Test
  void failureVerifyNotReady() throws Exception {
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_NO_CONTENT);
    ConflictException thrown = assertThrows(ConflictException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("Not ready");
  }
}
