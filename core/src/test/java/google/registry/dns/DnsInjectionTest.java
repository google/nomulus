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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertHostDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequestsExcept;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.RequestModule;
import google.registry.testing.CloudTasksHelper.CloudTasksHelperModule;
import google.registry.testing.FakeClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for Dagger injection of the DNS package. */
public final class DnsInjectionTest {

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);
  private final StringWriter httpOutput = new StringWriter();
  private final FakeClock clock = new FakeClock(DateTime.parse("2014-01-01TZ"));
  private DnsTestComponent component;

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
    component =
        DaggerDnsTestComponent.builder()
            .requestModule(new RequestModule(req, rsp))
            .cloudTasksHelperModule(new CloudTasksHelperModule(clock))
            .build();
    createTld("lol");
  }

  @Test
  void testReadDnsRefreshRequestsAction_injectsAndWorks() {
    persistActiveSubordinateHost("ns1.example.lol", persistActiveDomain("example.lol"));
    clock.advanceOneMilli();
    tm().transact(() -> DnsUtils.requestDomainDnsRefresh("example.lol"));
    when(req.getParameter("tld")).thenReturn("lol");
    clock.advanceOneMilli();
    component.readDnsRefreshRequestsAction().run();
    assertNoDnsRequests();
  }

  @Test
  void testRefreshDns_domain_injectsAndWorks() {
    persistActiveDomain("example.lol");
    when(req.getParameter("type")).thenReturn("domain");
    when(req.getParameter("name")).thenReturn("example.lol");
    component.refreshDns().run();
    assertNoDnsRequestsExcept("example.lol");
  }

  @Test
  void testRefreshDns_missingDomain_throwsNotFound() {
    when(req.getParameter("type")).thenReturn("domain");
    when(req.getParameter("name")).thenReturn("example.lol");
    NotFoundException thrown =
        assertThrows(NotFoundException.class, () -> component.refreshDns().run());
    assertThat(thrown).hasMessageThat().contains("domain example.lol not found");
  }

  @Test
  void testRefreshDns_host_injectsAndWorks() {
    persistActiveSubordinateHost("ns1.example.lol", persistActiveDomain("example.lol"));
    when(req.getParameter("type")).thenReturn("host");
    when(req.getParameter("name")).thenReturn("ns1.example.lol");
    component.refreshDns().run();
    assertHostDnsRequests("ns1.example.lol");
  }

  @Test
  void testRefreshDns_missingHost_throwsNotFound() {
    when(req.getParameter("type")).thenReturn("host");
    when(req.getParameter("name")).thenReturn("ns1.example.lol");
    NotFoundException thrown =
        assertThrows(NotFoundException.class, () -> component.refreshDns().run());
    assertThat(thrown).hasMessageThat().contains("host ns1.example.lol not found");
  }
}
