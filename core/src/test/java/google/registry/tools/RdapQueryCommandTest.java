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
import google.registry.request.Action.GkeService;
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

    when(mockDefaultConnection.withService(eq(GkeService.PUBAPI), eq(false)))
        .thenReturn(mockPubapiConnection);
  }

  private void mockGetResponse(
      String path, ImmutableMap<String, ?> queryParams, String responseBody) throws IOException {
    when(mockPubapiConnection.sendGetRequest(eq(path), eq(queryParams))).thenReturn(responseBody);
  }

  @Test
  void testSuccess_domainLookup() throws Exception {
    String path = "/rdap/domain/example.dev";
    mockGetResponse(path, ImmutableMap.of(), "{}");
    runCommand("--type=DOMAIN", "example.dev");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
  }

  @Test
  void testSuccess_domainSearch() throws Exception {
    String path = "/rdap/domains";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "exam*.dev");
    mockGetResponse(path, query, "{}");
    runCommand("--type=DOMAINSEARCH", "exam*.dev");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
  }

  @Test
  void testSuccess_entitySearch() throws Exception {
    String path = "/rdap/entities";
    ImmutableMap<String, String> query = ImmutableMap.of("fn", "John*");
    mockGetResponse(path, query, "{}");
    runCommand("--type=ENTITYSEARCH", "John*");
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
  void testFailure_missingQueryTerm_forDomain() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--type=DOMAIN"));
    assertThat(thrown).hasMessageThat().contains("A query term is required for a DOMAIN lookup.");
  }

  @Test
  void testFailure_hasQueryTerm_forHelp() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--type=HELP", "foo"));
    assertThat(thrown).hasMessageThat().contains("The HELP query does not take a query term.");
  }

  @Test
  void testFailure_missingMainParameter() {
    // JCommander throws a ParameterException if a required parameter is missing.
    assertThrows(ParameterException.class, this::runCommand);
  }
}
