// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatabaseHelper.assertBillingEventsForResource;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.flows.EppTestCase;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.TestCacheExtension;
import google.registry.util.Clock;
import java.time.Duration;
import java.util.List;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for tools that affect EPP lifecycle. */
class EppLifecycleToolsTest extends EppTestCase {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withClaimsListCache(Duration.ofHours(6)).build();

  @BeforeEach
  void beforeEach() {
    createTlds("example", "tld");
  }

  @Test
  void test_renewDomainThenUnrenew() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create the domain for 2 years.
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Explicitly renew it for 4 more years.
    assertThatCommand(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXPDATE", "2002-06-01", "YEARS", "4"))
        .atTime("2000-06-07T00:00:00Z")
        .hasResponse(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2006-06-01T00:02:00Z"));

    // Run an info command and verify its registration term is 6 years in total.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-08-07T00:01:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "UPDATE", "2000-06-12T00:00:00Z",
                "EXDATE", "2006-06-01T00:02:00Z"));

    assertThatCommand("poll.xml")
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_response_empty.xml");

    // Run the nomulus unrenew_domain command to take 3 years off the registration.
    clock.setTo(DateTime.parse("2001-06-07T00:00:00.0Z"));
    UnrenewDomainCommand unrenewCmd =
        new ForcedUnrenewDomainCommand(ImmutableList.of("example.tld"), 3, clock);
    unrenewCmd.run();

    // Run an info command and verify that the registration term is now 3 years in total.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2001-06-07T00:01:00.0Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "UPDATE", "2001-06-07T00:00:00Z",
                "EXDATE", "2003-06-01T00:02:00Z"));

    // Verify that the correct one-time poll message for the unrenew was sent.
    assertThatCommand("poll.xml")
        .atTime("2001-06-08T00:00:00Z")
        .hasResponse("poll_response_unrenew.xml");

    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "21-2001"))
        .atTime("2001-06-08T00:00:01Z")
        .hasResponse("poll_ack_response_empty.xml");

    // Run an info command after the 3 years to verify that the domain successfully autorenewed.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2003-06-02T00:00:00.0Z")
        .hasResponse(
            "domain_info_response_inactive_grace_period.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "UPDATE", "2003-06-01T00:02:00Z",
                "EXDATE", "2004-06-01T00:02:00Z",
                "RGPSTATUS", "autoRenewPeriod"));

    // And verify that the autorenew poll message worked as well.
    assertThatCommand("poll.xml")
        .atTime("2003-06-02T00:01:00Z")
        .hasResponse(
            "poll_response_autorenew.xml",
            ImmutableMap.of(
                "ID", "23-2003",
                "QDATE", "2003-06-01T00:02:00Z",
                "DOMAIN", "example.tld",
                "EXDATE", "2004-06-01T00:02:00Z"));

    // Assert about billing events.
    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    Domain domain =
        loadByForeignKey(Domain.class, "example.tld", DateTime.parse("2003-06-02T00:02:00Z")).get();
    BillingEvent renewBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.RENEW)
            .setTargetId(domain.getDomainName())
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setCost(Money.parse("USD 44.00"))
            .setPeriodYears(4)
            .setEventTime(DateTime.parse("2000-06-07T00:00:00Z"))
            .setBillingTime(DateTime.parse("2000-06-12T00:00:00Z"))
            .setDomainHistory(
                getOnlyHistoryEntryOfType(domain, Type.DOMAIN_RENEW, DomainHistory.class))
            .build();

    assertBillingEventsForResource(
        domain,
        makeOneTimeCreateBillingEvent(domain, createTime),
        renewBillingEvent,
        // The initial autorenew billing event, which was closed at the time of the explicit renew.
        makeRecurrence(
            domain,
            getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE, DomainHistory.class),
            createTime.plusYears(2),
            DateTime.parse("2000-06-07T00:00:00.000Z")),
        // The renew's autorenew billing event, which was closed at the time of the unrenew.
        makeRecurrence(
            domain,
            getOnlyHistoryEntryOfType(domain, Type.DOMAIN_RENEW, DomainHistory.class),
            DateTime.parse("2006-06-01T00:02:00.000Z"),
            DateTime.parse("2001-06-07T00:00:00.000Z")),
        // The remaining active autorenew billing event which was created by the unrenew.
        makeRecurrence(
            domain,
            getOnlyHistoryEntryOfType(domain, Type.SYNTHETIC, DomainHistory.class),
            DateTime.parse("2003-06-01T00:02:00.000Z"),
            END_OF_TIME));

    assertThatLogoutSucceeds();
  }

  static class ForcedUnrenewDomainCommand extends UnrenewDomainCommand {

    ForcedUnrenewDomainCommand(List<String> domainNames, int period, Clock clock) {
      super();
      this.clock = clock;
      this.force = true;
      this.mainParameters = domainNames;
      this.period = period;
    }
  }
}
