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
import static google.registry.testing.DatabaseHelper.assertHostDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequestsExcept;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.dns.DnsUtils.TargetType;
import google.registry.model.domain.Domain;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshDnsAction}. */
public class RefreshDnsActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock();

  private void run(TargetType type, String name) {
    new RefreshDnsAction(name, type, clock).run();
  }

  @BeforeEach
  void beforeEach() {
    createTld("xn--q9jyb4c");
  }

  @Test
  void testSuccess_host() {
    Domain domain = persistActiveDomain("example.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain);
    run(DnsUtils.TargetType.HOST, "ns1.example.xn--q9jyb4c");
    assertHostDnsRequests("ns1.example.xn--q9jyb4c");
    assertNoDnsRequestsExcept("ns1.example.xn--q9jyb4c");
  }

  @Test
  void testSuccess_externalHostNotEnqueued() {
    persistActiveDomain("example.xn--q9jyb4c");
    persistActiveHost("ns1.example.xn--q9jyb4c");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> {
              try {
                run(DnsUtils.TargetType.HOST, "ns1.example.xn--q9jyb4c");
              } finally {
                assertNoDnsRequests();
              }
            });
    assertThat(thrown)
        .hasMessageThat()
        .contains("ns1.example.xn--q9jyb4c isn't a subordinate hostname");
  }

  @Test
  void testSuccess_domain() {
    persistActiveDomain("example.xn--q9jyb4c");
    run(DnsUtils.TargetType.DOMAIN, "example.xn--q9jyb4c");
    assertDomainDnsRequests("example.xn--q9jyb4c");
    assertNoDnsRequestsExcept("example.xn--q9jyb4c");
  }

  @Test
  void testFailure_unqualifiedName() {
    assertThrows(BadRequestException.class, () -> run(DnsUtils.TargetType.DOMAIN, "example"));
  }

  @Test
  void testFailure_hostDoesNotExist() {
    assertThrows(
        NotFoundException.class, () -> run(DnsUtils.TargetType.HOST, "ns1.example.xn--q9jyb4c"));
  }

  @Test
  void testFailure_domainDoesNotExist() {
    assertThrows(
        NotFoundException.class, () -> run(DnsUtils.TargetType.DOMAIN, "example.xn--q9jyb4c"));
  }
}
