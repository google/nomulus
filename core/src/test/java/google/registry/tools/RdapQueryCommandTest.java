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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import google.registry.request.Action.Service;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link RdapQueryCommand}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RdapQueryCommandTest extends CommandTestCase<RdapQueryCommand> {

  @Mock private ServiceConnection mockDefaultConnection;
  @Mock private ServiceConnection mockPubapiConnection;

  @BeforeEach
  void beforeEach() {
    command.setConnection(mockDefaultConnection);
    command.useCanary = false;

    when(mockDefaultConnection.withService(Service.PUBAPI, false)).thenReturn(mockPubapiConnection);
  }

  private void mockGetResponse(
      String path, ImmutableMap<String, ?> queryParams, String responseBody) throws IOException {
    when(mockPubapiConnection.sendGetRequest(path, queryParams)).thenReturn(responseBody);
  }

  @Test
  void testSuccess_domainLookup() throws Exception {
    String path = "/rdap/domain/example.dev";
    String responseJson = "{\"ldhName\":\"example.dev\"}";
    mockGetResponse(path, ImmutableMap.of(), responseJson);

    runCommand("--type=DOMAIN_LOOKUP", "example.dev");
    verify(mockPubapiConnection).sendGetRequest(path, ImmutableMap.of());

    assertInStdout("{\n  \"ldhName\": \"example.dev\"\n}");
  }

  @Test
  void testSuccess_domainSearch() throws Exception {
    String path = "/rdap/domains";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "exam*.dev");
    String responseJson = "{\"domainSearchResults\":[{\"ldhName\":\"example.dev\"}]}";
    mockGetResponse(path, query, responseJson);

    runCommand("--type=DOMAIN_SEARCH", "exam*.dev");
    verify(mockPubapiConnection).sendGetRequest(path, query);

    assertInStdout(
        "{\n"
            + "  \"domainSearchResults\": [\n"
            + "    {\n"
            + "      \"ldhName\": \"example.dev\"\n"
            + "    }\n"
            + "  ]\n"
            + "}");
  }

  @Test
  void testSuccess_nameserverLookup() throws Exception {
    String path = "/rdap/nameserver/ns1.example.com";
    mockGetResponse(path, ImmutableMap.of(), "{}");
    runCommand("--type=NAMESERVER_LOOKUP", "ns1.example.com");
    verify(mockPubapiConnection).sendGetRequest(path, ImmutableMap.of());
    assertInStdout("{}\n");
  }

  @Test
  void testSuccess_nameserverSearch() throws Exception {
    String path = "/rdap/nameservers";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "ns*.example.com");
    mockGetResponse(path, query, "{}");
    runCommand("--type=NAMESERVER_SEARCH", "ns*.example.com");
    verify(mockPubapiConnection).sendGetRequest(path, query);
    assertInStdout("{}\n");
  }

  @Test
  void testSuccess_entityLookup() throws Exception {
    String path = "/rdap/entity/123-FOO";
    mockGetResponse(path, ImmutableMap.of(), "{}");
    runCommand("--type=ENTITY_LOOKUP", "123-FOO");
    verify(mockPubapiConnection).sendGetRequest(path, ImmutableMap.of());
    assertInStdout("{}\n");
  }

  @Test
  void testSuccess_entitySearch() throws Exception {
    String path = "/rdap/entities";
    ImmutableMap<String, String> query = ImmutableMap.of("fn", "John*");
    mockGetResponse(path, query, "{}");
    runCommand("--type=ENTITY_SEARCH", "John*");
    verify(mockPubapiConnection).sendGetRequest(path, query);
    assertInStdout("{}\n");
  }

  @Test
  void testFailure_missingType() {
    assertThrows(ParameterException.class, () -> runCommand("some-term"));
  }

  @Test
  void testFailure_missingQueryTerm() {
    assertThrows(ParameterException.class, () -> runCommand("--type=DOMAIN_LOOKUP"));
  }

  @Test
  void testFailure_propagatesIoException() throws IOException {
    String path = "/rdap/domain/fail.dev";
    when(mockPubapiConnection.sendGetRequest(path, ImmutableMap.of()))
        .thenThrow(new IOException("HTTP 500: Server on fire"));

    IOException thrown =
        assertThrows(IOException.class, () -> runCommand("--type=DOMAIN_LOOKUP", "fail.dev"));
    assertThat(thrown).hasMessageThat().contains("HTTP 500: Server on fire");
  }
}
