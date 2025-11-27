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

  // Helper to provide a safe, non-null return value to prevent NullPointerExceptions.
  private void mockGetRequest() throws IOException {
    when(mockPubapiConnection.sendGetRequest(
            (String) org.mockito.ArgumentMatchers.any(),
            (ImmutableMap<String, ?>) org.mockito.ArgumentMatchers.any()))
        .thenReturn("");
  }

  @Test
  void testSuccess_domainLookup() throws Exception {
    mockGetRequest();
    runCommand("--type=DOMAIN", "example.dev");
    verify(mockPubapiConnection).sendGetRequest("/rdap/domain/example.dev", ImmutableMap.of());
  }

  @Test
  void testSuccess_domainSearch() throws Exception {
    mockGetRequest();
    ImmutableMap<String, String> query = ImmutableMap.of("name", "exam*.dev");
    runCommand("--type=DOMAIN_SEARCH", "exam*.dev");
    verify(mockPubapiConnection).sendGetRequest("/rdap/domains", query);
  }

  @Test
  void testSuccess_nameserverLookup() throws Exception {
    mockGetRequest();
    runCommand("--type=NAMESERVER", "ns1.example.com");
    verify(mockPubapiConnection)
        .sendGetRequest("/rdap/nameserver/ns1.example.com", ImmutableMap.of());
  }

  @Test
  void testSuccess_nameserverSearch() throws Exception {
    mockGetRequest();
    ImmutableMap<String, String> query = ImmutableMap.of("name", "ns*.example.com");
    runCommand("--type=NAMESERVER_SEARCH", "ns*.example.com");
    verify(mockPubapiConnection).sendGetRequest("/rdap/nameservers", query);
  }

  @Test
  void testSuccess_entityLookup() throws Exception {
    mockGetRequest();
    runCommand("--type=ENTITY", "123-FOO");
    verify(mockPubapiConnection).sendGetRequest("/rdap/entity/123-FOO", ImmutableMap.of());
  }

  @Test
  void testSuccess_entitySearch() throws Exception {
    mockGetRequest();
    ImmutableMap<String, String> query = ImmutableMap.of("fn", "John*");
    runCommand("--type=ENTITY_SEARCH", "John*");
    verify(mockPubapiConnection).sendGetRequest("/rdap/entities", query);
  }

  @Test
  void testFailure_missingType() {
    assertThrows(ParameterException.class, () -> runCommand("some-term"));
  }

  @Test
  void testFailure_missingQueryTerm() {
    assertThrows(ParameterException.class, () -> runCommand("--type=DOMAIN"));
  }
}
