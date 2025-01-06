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

package google.registry.dns;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshDnsOnHostRenameAction}. */
public class RefreshDnsOnHostRenameActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2015-01-15T11:22:33Z"));
  private final FakeResponse response = new FakeResponse();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private RefreshDnsOnHostRenameAction action;

  private void createAction(String hostKey) {
    action = new RefreshDnsOnHostRenameAction(hostKey, response);
  }

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess() {
    Host host = persistActiveHost("ns1.example.tld");
    persistResource(newDomain("example.tld", host));
    persistResource(newDomain("otherexample.tld", host));
    persistResource(newDomain("untouched.tld", persistActiveHost("ns2.example.tld")));
    persistResource(
        newDomain("suspended.tld", host)
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .build());
    persistDomainAsDeleted(newDomain("deleted.tld", host), clock.nowUtc().minusDays(1));
    createAction(host.createVKey().stringify());
    action.run();
    assertDomainDnsRequests("example.tld", "otherexample.tld");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
  }

  @Test
  void testFailure_nonexistentHost() {
    createAction("kind:Host@sql:rO0ABXQABGJsYWg");
    action.run();
    assertNoDnsRequests();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Host to refresh does not exist: VKey<Host>(sql:blah)");
  }

  @Test
  void testFailure_deletedHost() {
    Host host = persistDeletedHost("ns1.example.tld", clock.nowUtc().minusDays(1));
    persistResource(newDomain("example.tld", host));
    createAction(host.createVKey().stringify());
    action.run();
    assertNoDnsRequests();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Host to refresh is already deleted: ns1.example.tld");
  }
}
