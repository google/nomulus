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

package google.registry.dns;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistActiveSubordinateHost;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.domain.DomainBase;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshDnsAction}. */
public class RefreshDnsActionTest {

  @RegisterExtension
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private final FakeClock clock = new FakeClock();

  private void run(TargetType type, String name) {
    new RefreshDnsAction(name, type, clock, dnsQueue).run();
  }

  @BeforeEach
  void beforeEach() {
    createTld("xn--q9jyb4c");
  }

  @Test
  void testSuccess_host() {
    DomainBase domain = persistActiveDomain("example.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain);
    run(TargetType.HOST, "ns1.example.xn--q9jyb4c");
    verify(dnsQueue).addHostRefreshTask("ns1.example.xn--q9jyb4c");
    verifyNoMoreInteractions(dnsQueue);
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
                run(TargetType.HOST, "ns1.example.xn--q9jyb4c");
              } finally {
                verifyNoMoreInteractions(dnsQueue);
              }
            });
    assertThat(thrown)
        .hasMessageThat()
        .contains("ns1.example.xn--q9jyb4c isn't a subordinate hostname");
  }

  @Test
  void testSuccess_domain() {
    persistActiveDomain("example.xn--q9jyb4c");
    run(TargetType.DOMAIN, "example.xn--q9jyb4c");
    verify(dnsQueue).addDomainRefreshTask("example.xn--q9jyb4c");
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testFailure_unqualifiedName() {
    assertThrows(BadRequestException.class, () -> run(TargetType.DOMAIN, "example"));
  }

  @Test
  void testFailure_hostDoesNotExist() {
    assertThrows(NotFoundException.class, () -> run(TargetType.HOST, "ns1.example.xn--q9jyb4c"));
  }

  @Test
  void testFailure_domainDoesNotExist() {
    assertThrows(NotFoundException.class, () -> run(TargetType.DOMAIN, "example.xn--q9jyb4c"));
  }
}
