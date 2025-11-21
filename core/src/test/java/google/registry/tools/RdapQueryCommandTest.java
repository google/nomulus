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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.TestLogHandler;
import google.registry.request.Action.Service;
import java.io.IOException;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
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

  private final TestLogHandler logHandler = new TestLogHandler();
  private static final Logger logger = Logger.getLogger(RdapQueryCommand.class.getName());

  @BeforeEach
  void beforeEach() {
    command.setConnection(mockDefaultConnection);
    command.useCanary = false;
    logger.addHandler(logHandler);

    when(mockDefaultConnection.withService(eq(Service.PUBAPI), eq(false)))
        .thenReturn(mockPubapiConnection);
  }

  @AfterEach
  void afterEach() {
    logger.removeHandler(logHandler);
  }

  private void mockGetResponse(
      String path, ImmutableMap<String, ?> queryParams, String responseBody) throws IOException {
    when(mockPubapiConnection.sendGetRequest(eq(path), eq(queryParams))).thenReturn(responseBody);
  }

  private String getJsonLogOutput() {
    return logHandler.getStoredLogRecords().stream()
        .map(record -> record.getMessage())
        .filter(message -> message.startsWith("{\n"))
        .findFirst()
        .orElse("");
  }

  @Test
  void testSuccess_domainLookup() throws Exception {
    String path = "/rdap/domain/example.dev";
    String responseJson = "{\"ldhName\":\"example.dev\"}";
    mockGetResponse(path, ImmutableMap.of(), responseJson);
    runCommand("--type=DOMAIN", "example.dev");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
    assertThat(getJsonLogOutput()).isEqualTo("{\n  \"ldhName\": \"example.dev\"\n}");
  }

  @Test
  void testSuccess_domainSearch() throws Exception {
    String path = "/rdap/domains";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "exam*.dev");
    String responseJson = "{\"domainSearchResults\":[{\"ldhName\":\"example.dev\"}]}";
    mockGetResponse(path, query, responseJson);
    runCommand("--type=DOMAIN_SEARCH", "exam*.dev");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
    assertThat(getJsonLogOutput())
        .isEqualTo(
            "{\n"
                + "  \"domainSearchResults\": [\n"
                + "    {\n"
                + "      \"ldhName\": \"example.dev\"\n"
                + "    }\n"
                + "  ]\n"
                + "}");
  }

  @Test
  void testSuccess_nameserverSearch() throws Exception {
    String path = "/rdap/nameservers";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "ns1.example.com");
    mockGetResponse(path, query, "{}");
    runCommand("--type=NAMESERVER_SEARCH", "ns1.example.com");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
  }

  @Test
  void testSuccess_entitySearch() throws Exception {
    String path = "/rdap/entities";
    ImmutableMap<String, String> query = ImmutableMap.of("fn", "John*");
    mockGetResponse(path, query, "{}");
    runCommand("--type=ENTITY_SEARCH", "John*");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
  }

  @Test
  void testSuccess_help() throws Exception {
    String path = "/rdap/help";
    mockGetResponse(path, ImmutableMap.of(), "{}");
    runCommand("--type=HELP");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
  }

  @Test
  void testFailure_propagatesIoException() throws IOException {
    String path = "/rdap/domain/fail.dev";
    when(mockPubapiConnection.sendGetRequest(eq(path), eq(ImmutableMap.of())))
        .thenThrow(new IOException("HTTP 500: Server on fire"));
    IOException thrown =
        assertThrows(IOException.class, () -> runCommand("--type=DOMAIN", "fail.dev"));
    assertThat(thrown).hasMessageThat().contains("HTTP 500: Server on fire");
  }

  @Test
  void testFailure_missingQueryTerm() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--type=DOMAIN"));
    assertThat(thrown).hasMessageThat().contains("A query term is required");
  }

  @Test
  void testFailure_queryTermProvidedForHelp() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--type=HELP", "foo"));
    assertThat(thrown).hasMessageThat().contains("A query term is not required for type HELP");
  }

  @Test
  void testFailure_missingType() {
    assertThrows(ParameterException.class, () -> runCommand("some-term"));
  }
}
