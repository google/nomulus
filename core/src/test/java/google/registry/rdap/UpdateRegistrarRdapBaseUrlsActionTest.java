// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonSyntaxException;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateRegistrarRdapBaseUrlsAction}. */
@DualDatabaseTest
public final class UpdateRegistrarRdapBaseUrlsActionTest {

  /**
   * Example reply from the MoSAPI server.
   *
   * <p>This is the exact reply we got from the server when we tried to access it manually, with the
   * addition of the 4000 and 4001 ones to test "multiple iana/servers in an element"
   *
   * <p>NOTE that 4000 has the same URL twice to make sure it doesn't break
   * ImmutableSetMultimap.Builder
   *
   * <p>Also added value for IANA ID 9999, so we can check non-REAL registrars
   */
  private static final String JSON_LIST_REPLY =
      "{\"publication\":\"2019-06-04T13:02:06Z\","
          + "\"description\":\"ICANN-accredited Registrar's RDAP base URL list\","
          + "\"services\":["
          + "[[\"81\"],[\"https://rdap.gandi.net\"]],"
          + "[[\"100\"],[\"https://yesnic.com/?_task=main&amp;_action=whois_search\"]],"
          + "[[\"134\"],[\"https://rdap.bb-online.com\"]],"
          + "[[\"1316\"],[\"https://whois.35.com\"]],"
          + "[[\"1448\"],[\"https://rdap.blacknight.com\"]],"
          + "[[\"1463\"],[\"https://rdap.domaincostclub.com/\"]],"
          + "[[\"99999\"],[\"https://rdaptest.com\"]],"
          + "[[\"1556\"],[\"https://rdap.west.cn\"]],"
          + "[[\"2288\"],[\"https://rdap.metaregistrar.com\"]],"
          + "[[\"4000\",\"4001\"],[\"https://rdap.example.com\"]],"
          + "[[\"4000\"],[\"https://rdap.example.net\",\"https://rdap.example.org\"]],"
          + "[[\"4000\"],[\"https://rdap.example.net\"]],"
          + "[[\"9999\"],[\"https://rdap.example.net\"]]"
          + "],"
          + "\"version\":\"1.0\"}";

  @RegisterExtension
  public AppEngineExtension appEngineRule =
      new AppEngineExtension.Builder().withDatastoreAndCloudSql().build();

  private static class TestHttpTransport extends MockHttpTransport {
    private final ArrayList<MockLowLevelHttpRequest> requestsSent = new ArrayList<>();
    private final ArrayList<MockLowLevelHttpResponse> simulatedResponses = new ArrayList<>();

    void addNextResponse(MockLowLevelHttpResponse response) {
      simulatedResponses.add(response);
    }

    List<MockLowLevelHttpRequest> getRequestsSent() {
      return requestsSent;
    }

    @Override
    public LowLevelHttpRequest buildRequest(String method, String url) {
      assertThat(method).isEqualTo("GET");
      MockLowLevelHttpRequest httpRequest = new MockLowLevelHttpRequest(url);
      httpRequest.setResponse(simulatedResponses.get(requestsSent.size()));
      requestsSent.add(httpRequest);
      return httpRequest;
    }
  }

  private TestHttpTransport httpTransport;
  private UpdateRegistrarRdapBaseUrlsAction action;

  @BeforeEach
  void beforeEach() {
    httpTransport = new TestHttpTransport();
    action = new UpdateRegistrarRdapBaseUrlsAction();

    action.password = "myPassword";
    action.httpTransport = httpTransport;
    addValidResponses(httpTransport);

    createTld("tld");
  }

  private void assertCorrectRequestsSent() {
    // Doing assertThat on the "getUrl()" of the elements to get better error message if we have the
    // wrong number of requests.
    // This way we'll see which URLs were hit on failure.
    assertThat(httpTransport.getRequestsSent().stream().map(request -> request.getUrl()))
        .hasSize(3);

    MockLowLevelHttpRequest loginRequest = httpTransport.getRequestsSent().get(0);
    MockLowLevelHttpRequest listRequest = httpTransport.getRequestsSent().get(1);
    MockLowLevelHttpRequest logoutRequest = httpTransport.getRequestsSent().get(2);

    assertThat(loginRequest.getUrl()).isEqualTo("https://mosapi.icann.org/mosapi/v1/tld/login");
    // base64.b64encode("tld_ry:myPassword") gives "dGxkX3J5Om15UGFzc3dvcmQ="
    assertThat(loginRequest.getHeaders())
        .containsEntry("authorization", ImmutableList.of("Basic dGxkX3J5Om15UGFzc3dvcmQ="));

    assertThat(listRequest.getUrl())
        .isEqualTo("https://mosapi.icann.org/mosapi/v1/tld/registrarRdapBaseUrl/list");
    assertThat(listRequest.getHeaders())
        .containsEntry("cookie", ImmutableList.of("id=myAuthenticationId"));

    assertThat(logoutRequest.getUrl()).isEqualTo("https://mosapi.icann.org/mosapi/v1/tld/logout");
    assertThat(logoutRequest.getHeaders())
        .containsEntry("cookie", ImmutableList.of("id=myAuthenticationId"));
  }

  private static void persistRegistrar(
      String registrarId, Long ianaId, Registrar.Type type, String... rdapBaseUrls) {
    persistSimpleResource(
        new Registrar.Builder()
            .setRegistrarId(registrarId)
            .setRegistrarName(registrarId)
            .setType(type)
            .setIanaIdentifier(ianaId)
            .setRdapBaseUrls(ImmutableSet.copyOf(rdapBaseUrls))
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 fake st"))
                    .setCity("fakeCity")
                    .setCountryCode("XX")
                    .build())
            .build());
  }

  @TestOfyAndSql
  void testUnknownIana_cleared() {
    // The IANA ID isn't in the JSON_LIST_REPLY
    persistRegistrar("someRegistrar", 4123L, Registrar.Type.REAL, "http://rdap.example/blah");

    action.run();

    assertCorrectRequestsSent();

    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls()).isEmpty();
  }

  @TestOfyAndSql
  void testKnownIana_changed() {
    // The IANA ID is in the JSON_LIST_REPLY
    persistRegistrar("someRegistrar", 1448L, Registrar.Type.REAL, "http://rdap.example/blah");

    action.run();

    assertCorrectRequestsSent();

    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("https://rdap.blacknight.com");
  }

  @TestOfyAndSql
  void testKnownIana_notReal_noChange() {
    // The IANA ID is in the JSON_LIST_REPLY
    persistRegistrar("someRegistrar", 9999L, Registrar.Type.INTERNAL, "http://rdap.example/blah");

    action.run();

    assertCorrectRequestsSent();

    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("http://rdap.example/blah");
  }

  @TestOfyAndSql
  void testKnownIana_notReal_nullIANA_noChange() {
    persistRegistrar("someRegistrar", null, Registrar.Type.TEST, "http://rdap.example/blah");

    action.run();

    assertCorrectRequestsSent();

    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("http://rdap.example/blah");
  }

  @TestOfyAndSql
  void testKnownIana_multipleValues() {
    // The IANA ID is in the JSON_LIST_REPLY
    persistRegistrar("registrar4000", 4000L, Registrar.Type.REAL, "http://rdap.example/blah");
    persistRegistrar("registrar4001", 4001L, Registrar.Type.REAL, "http://rdap.example/blah");

    action.run();

    assertCorrectRequestsSent();

    assertThat(loadRegistrar("registrar4000").getRdapBaseUrls())
        .containsExactly(
            "https://rdap.example.com", "https://rdap.example.net", "https://rdap.example.org");
    assertThat(loadRegistrar("registrar4001").getRdapBaseUrls())
        .containsExactly("https://rdap.example.com");
  }

  @TestOfyAndSql
  void testNoTlds() {
    deleteTld("tld");
    assertThat(assertThrows(IllegalArgumentException.class, action::run))
        .hasMessageThat()
        .isEqualTo("There must exist at least one REAL TLD.");
  }

  @TestOfyAndSql
  void testOnlyTestTlds() {
    persistResource(Registry.get("tld").asBuilder().setTldType(TldType.TEST).build());
    assertThat(assertThrows(IllegalArgumentException.class, action::run))
        .hasMessageThat()
        .isEqualTo("There must exist at least one REAL TLD.");
  }

  @TestOfyAndSql
  void testSecondTldSucceeds() {
    createTld("secondtld");
    httpTransport = new TestHttpTransport();
    action.httpTransport = httpTransport;

    // the first TLD request will return a bad cookie but the second will succeed
    MockLowLevelHttpResponse badLoginResponse = new MockLowLevelHttpResponse();
    badLoginResponse.addHeader(
        "Set-Cookie",
        "Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    httpTransport.addNextResponse(badLoginResponse);
    addValidResponses(httpTransport);

    action.run();
  }

  @TestOfyAndSql
  void testBothFail() {
    createTld("secondtld");
    httpTransport = new TestHttpTransport();
    action.httpTransport = httpTransport;

    MockLowLevelHttpResponse badLoginResponse = new MockLowLevelHttpResponse();
    badLoginResponse.addHeader(
        "Set-Cookie",
        "Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    // it should fail for both TLDs
    httpTransport.addNextResponse(badLoginResponse);
    httpTransport.addNextResponse(badLoginResponse);

    assertThat(assertThrows(RuntimeException.class, action::run))
        .hasMessageThat()
        .isEqualTo("Error contacting MosAPI server. Tried TLDs [secondtld, tld]");
  }

  @TestOfyAndSql
  void testFailureCause_ignoresLoginFailure() {
    // Login failures aren't particularly interesting so we should log them, but the final
    // throwable should be some other failure if one existed
    createTld("secondtld");
    httpTransport = new TestHttpTransport();
    action.httpTransport = httpTransport;

    MockLowLevelHttpResponse loginResponse = new MockLowLevelHttpResponse();
    loginResponse.addHeader(
        "Set-Cookie",
        "id=myAuthenticationId; "
            + "Expires=Tue, 11-Jun-2019 16:34:21 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    MockLowLevelHttpResponse badListResponse = new MockLowLevelHttpResponse();
    String badListReply = JSON_LIST_REPLY.substring(50);
    badListResponse.setContent(badListReply);

    MockLowLevelHttpResponse logoutResponse = new MockLowLevelHttpResponse();
    logoutResponse.addHeader(
        "Set-Cookie",
        "id=id; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    MockLowLevelHttpResponse badLoginResponse = new MockLowLevelHttpResponse();
    badLoginResponse.addHeader(
        "Set-Cookie",
        "Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    httpTransport.addNextResponse(loginResponse);
    httpTransport.addNextResponse(badListResponse);
    httpTransport.addNextResponse(logoutResponse);
    httpTransport.addNextResponse(badLoginResponse);

    assertThat(assertThrows(RuntimeException.class, action::run))
        .hasCauseThat()
        .isInstanceOf(JsonSyntaxException.class);
  }

  private static void addValidResponses(TestHttpTransport httpTransport) {
    MockLowLevelHttpResponse loginResponse = new MockLowLevelHttpResponse();
    loginResponse.addHeader(
        "Set-Cookie",
        "JSESSIONID=bogusid; " + "Expires=Tue, 11-Jun-2019 16:34:21 GMT; Path=/; Secure; HttpOnly");
    loginResponse.addHeader(
        "Set-Cookie",
        "id=myAuthenticationId; "
            + "Expires=Tue, 11-Jun-2019 16:34:21 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");

    MockLowLevelHttpResponse listResponse = new MockLowLevelHttpResponse();
    listResponse.setContent(JSON_LIST_REPLY);

    MockLowLevelHttpResponse logoutResponse = new MockLowLevelHttpResponse();
    logoutResponse.addHeader(
        "Set-Cookie",
        "id=id; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/mosapi/v1/app; Secure; HttpOnly");
    httpTransport.addNextResponse(loginResponse);
    httpTransport.addNextResponse(listResponse);
    httpTransport.addNextResponse(logoutResponse);
  }
}
