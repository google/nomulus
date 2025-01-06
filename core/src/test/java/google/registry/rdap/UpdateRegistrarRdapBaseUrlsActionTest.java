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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarBase;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.testing.FakeUrlConnectionService;
import google.registry.util.UrlConnectionException;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateRegistrarRdapBaseUrlsAction}. */
public final class UpdateRegistrarRdapBaseUrlsActionTest {

  // This reply simulates part of the actual IANA CSV reply
  private static final String CSV_REPLY =
      """
          "ID",Registrar Name,Status,RDAP Base URL
          1,Reserved,Reserved,
          81,Gandi SAS,Accredited,https://rdap.gandi.net/
          100,Whois Corp.,Accredited,https://www.yesnic.com/rdap/
          134,BB-Online UK Limited,Accredited,https://rdap.bb-online.com/
          1316,"Xiamen 35.Com Technology Co., Ltd.",Accredited,https://rdap.35.com/rdap/
          1448,Blacknight Internet Solutions Ltd.,Accredited,https://rdap.blacknight.com/
          1463,"Global Domains International, Inc. DBA DomainCostClub.com",Accredited,\
          https://rdap.domaincostclub.com/
          1556,"Chengdu West Dimension Digital Technology Co., Ltd.",Accredited,\
          https://rdap.west.cn/rdap/
          2288,Metaregistrar BV,Accredited,https://rdap.metaregistrar.com/
          4000,Gname 031 Inc,Accredited,
          9999,Reserved for non-billable transactions where Registry Operator acts as\
           Registrar,Reserved,
          """;

  @RegisterExtension
  public JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final HttpURLConnection connection = mock(HttpURLConnection.class);
  private final FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(connection);
  private UpdateRegistrarRdapBaseUrlsAction action;

  @BeforeEach
  void beforeEach() throws Exception {
    action = new UpdateRegistrarRdapBaseUrlsAction();
    action.urlConnectionService = urlConnectionService;
    when(connection.getResponseCode()).thenReturn(SC_OK);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream(CSV_REPLY.getBytes(StandardCharsets.UTF_8)));
    createTld("tld");
  }

  private void assertCorrectRequestSent() throws Exception {
    assertThat(urlConnectionService.getConnectedUrls())
        .containsExactly(
            new URL("https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv"));
    verify(connection).setRequestProperty("Accept-Encoding", "gzip");
  }

  private static void persistRegistrar(
      String registrarId, Long ianaId, RegistrarBase.Type type, String... rdapBaseUrls) {
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

  @Test
  void testUnknownIana_cleared() throws Exception {
    // The IANA ID isn't in the CSV reply
    persistRegistrar("someRegistrar", 4123L, Registrar.Type.REAL, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls()).isEmpty();
  }

  @Test
  void testKnownIana_changed() throws Exception {
    // The IANA ID is in the CSV reply
    persistRegistrar("someRegistrar", 1448L, Registrar.Type.REAL, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("https://rdap.blacknight.com/");
  }

  @Test
  void testKnownIana_notReal_noChange() throws Exception {
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
  void testKnownIana_notReal_nullIANA_noChange() throws Exception {
    persistRegistrar("someRegistrar", null, Registrar.Type.TEST, "http://rdap.example/blah");
    action.run();
    assertCorrectRequestSent();
    assertThat(loadRegistrar("someRegistrar").getRdapBaseUrls())
        .containsExactly("http://rdap.example/blah");
  }

  @Test
  void testFailure_serverErrorResponse() throws Exception {
    when(connection.getResponseCode()).thenReturn(SC_INTERNAL_SERVER_ERROR);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
    InternalServerErrorException thrown =
        assertThrows(InternalServerErrorException.class, action::run);
    verify(connection, times(0)).getInputStream();
    assertThat(thrown).hasMessageThat().isEqualTo("Error when retrieving RDAP base URL CSV file");
    Throwable cause = thrown.getCause();
    assertThat(cause).isInstanceOf(UrlConnectionException.class);
    assertThat(cause)
        .hasMessageThat()
        .contains("https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv");
  }

  @Test
  void testFailure_invalidCsv() throws Exception {
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("foo,bar\nbaz,foo".getBytes(StandardCharsets.UTF_8)));

    InternalServerErrorException thrown =
        assertThrows(InternalServerErrorException.class, action::run);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Mapping for ID not found, expected one of [foo, bar]");
  }
}
