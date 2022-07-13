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
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateRegistrarRdapBaseUrlsAction}. */
public final class UpdateRegistrarRdapBaseUrlsActionTest {

  // This reply simulates part of the actual IANA CSV reply
  private static final String CSV_REPLY =
      "\"ID\",Registrar Name,Status,RDAP Base URL\n"
          + "1,Reserved,Reserved,\n"
          + "81,Gandi SAS,Accredited,https://rdap.gandi.net/\n"
          + "100,Whois Corp.,Accredited,https://www.yesnic.com/rdap/\n"
          + "134,BB-Online UK Limited,Accredited,https://rdap.bb-online.com/\n"
          + "1316,\"Xiamen 35.Com Technology Co., Ltd.\",Accredited,https://rdap.35.com/rdap/\n"
          + "1448,Blacknight Internet Solutions Ltd.,Accredited,https://rdap.blacknight.com/\n"
          + "1463,\"Global Domains International, Inc. DBA"
          + " DomainCostClub.com\",Accredited,https://rdap.domaincostclub.com/\n"
          + "1556,\"Chengdu West Dimension Digital Technology Co.,"
          + " Ltd.\",Accredited,https://rdap.west.cn/rdap/\n"
          + "2288,Metaregistrar BV,Accredited,https://rdap.metaregistrar.com/\n"
          + "4000,Gname 031 Inc,Accredited,\n"
          + "9999,Reserved for non-billable transactions where Registry Operator acts as"
          + " Registrar,Reserved,\n";

  @RegisterExtension
  public AppEngineExtension appEngineExtension =
      new AppEngineExtension.Builder().withCloudSql().build();

  private static class TestHttpTransport extends MockHttpTransport {
    private MockLowLevelHttpRequest requestSent;
    private MockLowLevelHttpResponse response;

    void setResponse(MockLowLevelHttpResponse response) {
      this.response = response;
    }

    MockLowLevelHttpRequest getRequestSent() {
      return requestSent;
    }

    @Override
    public LowLevelHttpRequest buildRequest(String method, String url) {
      assertThat(method).isEqualTo("GET");
      MockLowLevelHttpRequest httpRequest = new MockLowLevelHttpRequest(url);
      httpRequest.setResponse(response);
      requestSent = httpRequest;
      return httpRequest;
    }
  }

  private TestHttpTransport httpTransport;
  private UpdateRegistrarRdapBaseUrlsAction action;

  @BeforeEach
  void beforeEach() {
    action = new UpdateRegistrarRdapBaseUrlsAction();
    httpTransport = new TestHttpTransport();
    action.httpTransport = httpTransport;
    setValidResponse();
    createTld("tld");
  }

  private void assertCorrectRequestSent() {
    assertThat(httpTransport.getRequestSent().getUrl())
        .isEqualTo("https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv");
    assertThat(httpTransport.getRequestSent().getHeaders().get("accept-encoding")).isNull();
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

  private void setValidResponse() {
    MockLowLevelHttpResponse csvResponse = new MockLowLevelHttpResponse();
    csvResponse.setContent(CSV_REPLY);
    httpTransport.setResponse(csvResponse);
  }

  @Test
  void testUnknownIana_cleared() {
    // The IANA ID isn't in the CSV reply
    persistRegistrar("someRegistrar", 4123L, Registrar.Type.REAL, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls()).isEmpty();
  }

  @Test
  void testKnownIana_changed() {
    // The IANA ID is in the CSV reply
    persistRegistrar("someRegistrar", 1448L, Registrar.Type.REAL, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("https://rdap.blacknight.com/");
  }

  @Test
  void testKnownIana_notReal_noChange() {
    // The IANA ID is in the CSV reply
    persistRegistrar("someRegistrar", 9999L, Registrar.Type.INTERNAL, "http://rdap.example/blah");
    // Real registrars should actually change
    persistRegistrar("otherRegistrar", 2288L, Registrar.Type.REAL, "http://old.example");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("http://rdap.example/blah");
    assertThat(loadRegistrar("otherRegistrar").getRdapBaseUrls())
        .containsExactly("https://rdap.metaregistrar.com/");
  }

  @Test
  void testKnownIana_notReal_nullIANA_noChange() {
    persistRegistrar("someRegistrar", null, Registrar.Type.TEST, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("http://rdap.example/blah");
  }

  @Test
  void testFailure_serverErrorResponse() {
    MockLowLevelHttpResponse badResponse = new MockLowLevelHttpResponse();
    badResponse.setZeroContent();
    badResponse.setStatusCode(HttpStatusCodes.STATUS_CODE_SERVER_ERROR);
    httpTransport.setResponse(badResponse);

    RuntimeException thrown = assertThrows(RuntimeException.class, action::run);
    assertThat(thrown).hasMessageThat().isEqualTo("Error when retrieving RDAP base URL CSV file");
    Throwable cause = thrown.getCause();
    assertThat(cause).isInstanceOf(HttpResponseException.class);
    assertThat(cause)
        .hasMessageThat()
        .isEqualTo("500\nGET https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv");
  }

  @Test
  void testFailure_invalidCsv() {
    MockLowLevelHttpResponse csvResponse = new MockLowLevelHttpResponse();
    csvResponse.setContent("foo,bar\nbaz,foo");
    httpTransport.setResponse(csvResponse);

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Mapping for ID not found, expected one of [foo, bar]");
  }
}
