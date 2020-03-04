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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.UrlFetchUtils.getHeaderFirst;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import google.registry.model.registry.Registry;
import google.registry.request.HttpException.ConflictException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import java.net.URL;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link NordnVerifyAction}. */
@RunWith(JUnit4.class)
public class NordnVerifyActionTest {

  private static final String LOG_ACCEPTED = "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
      + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,no-warnings,1\n"
      + "roid,result-code\n"
      + "SH8013-REP,2000";

  private static final String LOG_REJECTED = "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
      + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,rejected,no-warnings,1\n"
      + "roid,result-code\n"
      + "SH8013-REP,2001";

  private static final String LOG_WARNINGS = "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
      + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,warnings-present,3\n"
      + "roid,result-code\n"
      + "SH8013-REP,2001\n"
      + "lulz-roid,3609\n"
      + "sabokitty-roid,3610\n";

  private static final String LOG_ERRORS = "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
      + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,accepted,warnings-present,3\n"
      + "roid,result-code\n"
      + "SH8013-REP,2000\n"
      + "lulz-roid,4601\n"
      + "bogpog,4611\n";

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private URLFetchService fetchService;
  @Mock private HTTPResponse httpResponse;
  @Captor private ArgumentCaptor<HTTPRequest> httpRequestCaptor;

  private final FakeResponse response = new FakeResponse();
  private final LordnRequestInitializer lordnRequestInitializer =
      new LordnRequestInitializer(Optional.of("attack"));
  private final NordnVerifyAction action = new NordnVerifyAction();

  @Before
  public void before() throws Exception {
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
    when(httpResponse.getContent()).thenReturn(LOG_ACCEPTED.getBytes(UTF_8));
    when(fetchService.fetch(any(HTTPRequest.class))).thenReturn(httpResponse);
    createTld("gtld");
    persistResource(Registry.get("gtld").asBuilder().setLordnUsername("lolcat").build());
    action.tld = "gtld";
    action.fetchService = fetchService;
    action.lordnRequestInitializer = lordnRequestInitializer;
    action.response = response;
    action.url = new URL("http://127.0.0.1/blobio");
  }

  private HTTPRequest getCapturedHttpRequest() throws Exception {
    verify(fetchService).fetch(httpRequestCaptor.capture());
    return httpRequestCaptor.getAllValues().get(0);
  }

  @Test
  public void testSuccess_sendHttpRequest_urlIsCorrect() throws Exception {
    action.run();
    assertThat(getCapturedHttpRequest().getURL()).isEqualTo(new URL("http://127.0.0.1/blobio"));
  }

  @Test
  public void testSuccess_hasLordnPassword_sendsAuthorizationHeader() throws Exception {
    action.run();
    assertThat(getHeaderFirst(getCapturedHttpRequest(), AUTHORIZATION))
        .hasValue("Basic bG9sY2F0OmF0dGFjaw==");  // echo -n lolcat:attack | base64
  }

  @Test
  public void testSuccess_noLordnPassword_doesntSetAuthorizationHeader() throws Exception {
    action.lordnRequestInitializer = new LordnRequestInitializer(Optional.empty());
    action.run();
    assertThat(getHeaderFirst(getCapturedHttpRequest(), AUTHORIZATION)).isEmpty();
  }

  @Test
  public void successVerifyRejected() throws Exception {
    when(httpResponse.getContent()).thenReturn(LOG_REJECTED.getBytes(UTF_8));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.getStatus()).isEqualTo(LordnLog.Status.REJECTED);
  }

  @Test
  public void successVerifyWarnings() throws Exception {
    when(httpResponse.getContent()).thenReturn(LOG_WARNINGS.getBytes(UTF_8));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.hasWarnings()).isTrue();
  }

  @Test
  public void successVerifyErrors() throws Exception {
    when(httpResponse.getContent()).thenReturn(LOG_ERRORS.getBytes(UTF_8));
    LordnLog lastLog = action.verify();
    assertThat(lastLog.hasWarnings()).isTrue();
  }

  @Test
  public void failureVerifyUnauthorized() {
    when(httpResponse.getResponseCode()).thenReturn(SC_UNAUTHORIZED);
    assertThrows(Exception.class, action::run);
  }

  @Test
  public void failureVerifyNotReady() {
    when(httpResponse.getResponseCode()).thenReturn(SC_NO_CONTENT);
    ConflictException thrown = assertThrows(ConflictException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("Not ready");
  }
}
