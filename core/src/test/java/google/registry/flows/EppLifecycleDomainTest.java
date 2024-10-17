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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_AND_CLOSE;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatabaseHelper.assertBillingEventsForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppMetricSubject.assertThat;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.tmch.TmchData;
import google.registry.tmch.TmchTestData;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for domain lifecycle. */
class EppLifecycleDomainTest extends EppTestCase {

  private static final ImmutableMap<String, String> DEFAULT_TRANSFER_RESPONSE_PARMS =
      ImmutableMap.of(
          "REDATE", "2002-05-30T00:00:00Z",
          "ACDATE", "2002-06-04T00:00:00Z",
          "EXDATE", "2003-06-01T00:04:00Z");

  private static final String ENCODED_SMD =
      TmchData.readEncodedSignedMark(TmchTestData.loadFile("smd/active.smd")).getEncodedData();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTlds("example", "tld");
  }

  @Test
  void testDomainDeleteRestore() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-07T00:02:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-06-06T00:02:00Z"));

    // Delete domain example.tld after its add grace period has expired.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-01T00:02:00Z")
        .hasResponse("generic_success_action_pending_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-03T00:02:00Z")
        .hasResponse(
            "domain_info_response_redemptionperiod_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date doesn't change because the deletion didn't cancel any charges.
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-07-01T00:02:00Z"));

    // Restore the domain.
    assertThatCommand("domain_update_restore_request.xml")
        .atTime("2000-07-01T00:03:00Z")
        .hasResponse("generic_success_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-02T00:03:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-07-01T00:03:00Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeleteRestore_duringAutorenewGracePeriod() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-07T00:02:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-06-06T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2002-06-07T00:02:00Z")
        .hasResponse(
            "domain_info_response_graceperiod.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date has advanced 1 year because of autorenew.
                "EXDATE", "2003-06-01T00:02:00Z",
                // This is the time of the autorenew.
                "UPDATE", "2002-06-01T00:02:00Z",
                "GRACEPERIOD", "autoRenewPeriod"));

    // Delete domain example.tld during its autorenew grace period.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2002-07-01T00:02:00Z")
        .hasResponse("generic_success_action_pending_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2002-07-03T00:02:00Z")
        .hasResponse(
            "domain_info_response_redemptionperiod_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date reverts back to what it was originally because the deletion
                // canceled out the autorenew.
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2002-07-01T00:02:00Z"));

    // Restore the domain.
    assertThatCommand("domain_update_restore_request.xml")
        .atTime("2002-07-05T00:03:00Z")
        .hasResponse("generic_success_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2002-07-07T00:03:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2003-06-01T00:02:00Z",
                "UPDATE", "2002-07-05T00:03:00Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeleteRestore_duringRenewalGracePeriod() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-07T00:02:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-06-06T00:02:00Z"));

    assertThatCommand(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXPDATE", "2002-06-01", "YEARS", "3"))
        .atTime("2000-06-08T00:00:00Z")
        .hasResponse(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-10T00:02:00Z")
        .hasResponse(
            "domain_info_response_graceperiod.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date is 5 years in total after the create.
                "EXDATE", "2005-06-01T00:02:00Z",
                // This is the time of the renew.
                "UPDATE", "2000-06-08T00:00:00Z",
                "GRACEPERIOD", "renewPeriod"));

    // Delete domain example.tld during its renew grace period.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-12T00:00:00Z")
        .hasResponse("generic_success_action_pending_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-13T00:00:00Z")
        .hasResponse(
            "domain_info_response_redemptionperiod_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date reverts back to what it was originally because the deletion
                // canceled out the 3-year renewal.
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-06-12T00:00:00Z"));

    // Restore the domain.
    assertThatCommand("domain_update_restore_request.xml")
        .atTime("2000-06-20T00:00:00Z")
        .hasResponse("generic_success_response.xml");

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-21T00:00:00Z")
        .hasResponse(
            "domain_info_response_inactive.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z",
                "UPDATE", "2000-06-20T00:00:00Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDelete_duringAddAndRenewalGracePeriod_deletesImmediately() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(createTime)
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-02T00:02:00Z")
        .hasResponse(
            "domain_info_response_addperiod_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    DateTime renewTime = DateTime.parse("2000-06-03T00:00:00Z");
    assertThatCommand(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXPDATE", "2002-06-01", "YEARS", "3"))
        .atTime(renewTime)
        .hasResponse(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-06-01T00:02:00Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-03T03:00:00Z")
        .hasResponse(
            "domain_info_response_graceperiod_add_and_renew.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                // The exp. date is 5 years in total after the create.
                "EXDATE", "2005-06-01T00:02:00Z",
                // This is the time of the renew.
                "UPDATE", "2000-06-03T00:00:00Z"));

    Domain domain =
        loadByForeignKey(Domain.class, "example.tld", DateTime.parse("2000-06-03T04:00:00Z")).get();

    DateTime deleteTime = DateTime.parse("2000-06-04T00:00:00Z");
    // Delete domain example.tld during both grace periods.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-04T00:00:00Z")
        .hasResponse("generic_success_response.xml");

    // Verify that it is immediately non-existent.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-04T00:01:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303", "MSG", "The domain with given ID (example.tld) doesn't exist."));

    // The expected one-time billing event, that should have an associated Cancellation.
    BillingEvent createBillingEvent = makeOneTimeCreateBillingEvent(domain, createTime);
    BillingEvent renewBillingEvent = makeOneTimeRenewBillingEvent(domain, renewTime);

    // Verify that the OneTime billing event associated with the domain creation is canceled.
    assertBillingEventsForResource(
        domain,
        // There should be one-time billing events for the create and the renew.
        createBillingEvent,
        renewBillingEvent,
        // There should be two ended recurring billing events, one each from the create and renew.
        // (The former was ended by the renew and the latter was ended by the delete.)
        makeCreateRecurrence(domain, createTime.plusYears(2), renewTime),
        makeRenewRecurrence(domain, createTime.plusYears(5), deleteTime),
        // There should be Cancellations offsetting both of the one-times.
        makeCancellationBillingEventForCreate(domain, createBillingEvent, createTime, deleteTime),
        makeCancellationBillingEventForRenew(domain, renewBillingEvent, renewTime, deleteTime));

    // Verify that the registration expiration time was set back to the creation time, because the
    // entire cost of registration was refunded. We have to do this through the DB instead of EPP
    // because domains deleted during the add grace period vanish immediately as far as the world
    // outside our system is concerned.
    Domain deletedDomain = loadByEntity(domain);
    assertAboutDomains().that(deletedDomain).hasRegistrationExpirationTime(createTime);

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeletion_withinAddGracePeriod_deletesImmediately() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(createTime)
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00.0Z",
                "EXDATE", "2002-06-01T00:02:00.0Z"));

    Domain domain = loadByForeignKey(Domain.class, "example.tld", createTime.plusHours(1)).get();

    // Delete domain example.tld within the add grace period.
    DateTime deleteTime = createTime.plusDays(1);
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("generic_success_response.xml");

    // Verify that it is immediately non-existent.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime.plusSeconds(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303", "MSG", "The domain with given ID (example.tld) doesn't exist."));

    // The expected one-time billing event, that should have an associated Cancellation.
    BillingEvent createBillingEvent = makeOneTimeCreateBillingEvent(domain, createTime);
    // Verify that the OneTime billing event associated with the domain creation is canceled.
    assertBillingEventsForResource(
        domain,
        // Check the existence of the expected create one-time billing event.
        createBillingEvent,
        makeCreateRecurrence(domain, createTime.plusYears(2), deleteTime),
        // Check for the existence of a cancellation for the given one-time billing event.
        makeCancellationBillingEventForCreate(domain, createBillingEvent, createTime, deleteTime));

    // Verify that the registration expiration time was set back to the creation time, because the
    // entire cost of registration was refunded. We have to do this through the DB instead of EPP
    // because domains deleted during the add grace period vanish immediately as far as the world
    // outside our system is concerned.
    Domain deletedDomain = loadByEntity(domain);
    assertAboutDomains().that(deletedDomain).hasRegistrationExpirationTime(createTime);

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeletion_outsideAddGracePeriod_showsRedemptionPeriod() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(createTime)
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00.0Z",
                "EXDATE", "2002-06-01T00:02:00.0Z"));

    DateTime deleteTime = DateTime.parse("2000-07-07T00:02:00Z"); // 1 month and 6 days after
    // Delete domain example.tld after its add grace period has expired.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("generic_success_action_pending_response.xml");

    // Verify that domain shows redemptionPeriod soon after deletion.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-08T00:00:00Z")
        .hasResponse(
            "domain_info_response_wildcard.xml", ImmutableMap.of("STATUS", "redemptionPeriod"));

    // Verify that the domain shows pendingDelete next.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-08-08T00:00:00Z")
        .hasResponse(
            "domain_info_response_wildcard_after_redemption.xml",
            ImmutableMap.of("STATUS", "pendingDelete"));

    // Verify that the domain is non-existent (available for registration) later.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-09-01T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303", "MSG", "The domain with given ID (example.tld) doesn't exist."));

    Domain domain =
        loadByForeignKey(Domain.class, "example.tld", DateTime.parse("2000-08-01T00:02:00Z")).get();
    // Verify that the autorenew was ended and that the one-time billing event is not canceled.
    assertBillingEventsForResource(
        domain,
        makeOneTimeCreateBillingEvent(domain, createTime),
        makeCreateRecurrence(domain, createTime.plusYears(2), deleteTime));

    assertThatLogoutSucceeds();

    // Make sure that in the future, the domain expiration is unchanged after deletion
    Domain clonedDomain = domain.cloneProjectedAtTime(deleteTime.plusYears(5));
    assertThat(clonedDomain.getRegistrationExpirationTime()).isEqualTo(createTime.plusYears(2));
  }

  @Test
  void testEapDomainDeletion_withinAddGracePeriod_eapFeeIsNotRefunded() throws Exception {
    assertThatCommand("login_valid_fee_extension.xml").hasSuccessfulLogin();
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Set the EAP schedule.
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 100),
                    DateTime.parse("2000-06-02T00:00:00Z"),
                    Money.of(USD, 0)))
            .build());

    // Create domain example.tld, which should have an EAP fee of USD 100.
    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    assertThatCommand("domain_create_eap_fee.xml")
        .atTime(createTime)
        .hasResponse("domain_create_response_eap_fee.xml");

    Domain domain =
        loadByForeignKey(Domain.class, "example.tld", DateTime.parse("2000-06-01T00:03:00Z")).get();

    // Delete domain example.tld within the add grade period.
    DateTime deleteTime = createTime.plusDays(1);
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("domain_delete_response_fee.xml");

    // Verify that the OneTime billing event associated with the base fee of domain registration and
    // is canceled and the autorenew is ended, but that the EAP fee is not canceled.
    BillingEvent expectedCreateEapBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.FEE_EARLY_ACCESS)
            .setTargetId("example.tld")
            .setRegistrarId("NewRegistrar")
            .setPeriodYears(1)
            .setCost(Money.parse("USD 100.00"))
            .setEventTime(createTime)
            .setBillingTime(createTime.plus(Tld.get("tld").getRenewGracePeriodLength()))
            .setDomainHistory(
                getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE, DomainHistory.class))
            .build();

    // The expected one-time billing event, that should have an associated Cancellation.
    BillingEvent expectedCreateBillingEvent = makeOneTimeCreateBillingEvent(domain, createTime);
    assertBillingEventsForResource(
        domain,
        // Check for the expected create one-time billing event ...
        expectedCreateBillingEvent,
        // ... and the expected one-time EAP fee billing event ...
        expectedCreateEapBillingEvent,
        makeCreateRecurrence(domain, createTime.plusYears(2), deleteTime),
        // ... and verify that the create one-time billing event was canceled ...
        makeCancellationBillingEventForCreate(
            domain, expectedCreateBillingEvent, createTime, deleteTime));
    // ... but there was NOT a Cancellation for the EAP fee, as this would fail if additional
    // billing events were present.

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeletionWithSubordinateHost_fails() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2305", "MSG", "Domain to be deleted has subordinate hosts"));
    assertThatLogoutSucceeds();
  }

  @Test
  void testDeletionOfDomain_afterRenameOfSubordinateHost_succeeds() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasNoTld()
        .and()
        .hasCommandName("Login")
        .and()
        .hasStatus(SUCCESS);
    createFakesite();
    createSubordinateHost();
    // Update the ns3 host to no longer be on fakesite.example domain.
    assertThatCommand("host_update_fakesite.xml")
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // Add assert about EppMetric
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostUpdate")
        .and()
        .hasStatus(SUCCESS);
    // Delete the fakesite.example domain (which should succeed since it no longer has subords).
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse("generic_success_action_pending_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasTld("example")
        .and()
        .hasCommandName("DomainDelete")
        .and()
        .hasStatus(SUCCESS_WITH_ACTION_PENDING);
    // Check info on the renamed host and verify that it's still around and wasn't deleted.
    assertThatCommand("host_info_ns9000_example.xml")
        .atTime("2002-06-30T01:03:00Z")
        .hasResponse("host_info_response_ns9000_example.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostInfo")
        .and()
        .hasStatus(SUCCESS);
    assertThatLogoutSucceeds();
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("Logout")
        .and()
        .hasStatus(SUCCESS_AND_CLOSE);
  }

  @Test
  void testDeletionOfDomain_afterUpdateThatCreatesSubordinateHost_fails() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-02T00:00:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-02T00:00:00.0Z",
                "EXDATE", "2002-06-02T00:00:00.0Z"));

    // Create nameserver ns1.example.tld
    assertThatCommand("host_create_example.xml")
        .atTime("2000-06-02T00:01:00Z")
        .hasResponse("host_create_response_example.xml");

    // Update the ns1 host to be on the fakesite.example domain.
    assertThatCommand("host_update_ns1_to_fakesite.xml")
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // Attempt to delete the fakesite.example domain (which should fail since it now has a
    // subordinate host).
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2305", "MSG", "Domain to be deleted has subordinate hosts"));
    // Check info on the renamed host and verify that it's still around and wasn't deleted.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2002-06-30T01:03:00Z")
        .hasResponse("host_info_response_fakesite_post_update.xml");
    // Verify that fakesite.example domain is still around and wasn't deleted.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_ok_post_host_update.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainCreation_failsBeforeSunrise() throws Exception {
    DateTime sunriseDate = DateTime.parse("2000-05-30T00:00:00Z");
    createTld(
        "example",
        new ImmutableSortedMap.Builder<DateTime, TldState>(Ordering.natural())
            .put(START_OF_TIME, PREDELEGATION)
            .put(sunriseDate, START_DATE_SUNRISE)
            .put(sunriseDate.plusMonths(2), GENERAL_AVAILABILITY)
            .build());

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");

    createContactsAndHosts();

    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(sunriseDate.minusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    assertThatCommand("domain_info_testvalidate.xml")
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303",
                "MSG", "The domain with given ID (test-validate.example) doesn't exist."));

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainCheckFee_succeeds() throws Exception {
    DateTime gaDate = DateTime.parse("2000-05-30T00:00:00Z");
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            gaDate, GENERAL_AVAILABILITY));

    assertThatCommand("login_valid_fee_extension.xml").hasSuccessfulLogin();

    assertThatCommand("domain_check_fee_premium.xml")
        .atTime(gaDate.plusDays(1))
        .hasResponse("domain_check_fee_premium_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("DomainCheck")
        .and()
        .hasTld("example")
        .and()
        .hasStatus(SUCCESS);

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainCreate_annualAutoRenewPollMessages_haveUniqueIds() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create the domain.
    createFakesite();

    // The first autorenew poll message isn't seen until after the initial two years of registration
    // are up.
    assertThatCommand("poll.xml")
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2002-07-01T00:01:00Z")
        .hasResponse(
            "poll_response_autorenew.xml",
            ImmutableMap.of(
                "ID", "15-2002",
                "QDATE", "2002-06-01T00:04:00Z",
                "DOMAIN", "fakesite.example",
                "EXDATE", "2003-06-01T00:04:00Z"));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "15-2002"))
        .atTime("2002-07-01T00:02:00Z")
        .hasResponse("poll_ack_response_empty.xml");

    // The second autorenew poll message isn't seen until after another year, and it should have a
    // different ID.
    assertThatCommand("poll.xml")
        .atTime("2002-07-01T00:05:00Z")
        .hasResponse("poll_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2003-07-01T00:05:00Z")
        .hasResponse(
            "poll_response_autorenew.xml",
            ImmutableMap.of(
                "ID", "15-2003", // Note -- Year is different from previous ID.
                "QDATE", "2003-06-01T00:04:00Z",
                "DOMAIN", "fakesite.example",
                "EXDATE", "2004-06-01T00:04:00Z"));

    // Ack the second poll message and verify that none remain.
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "15-2003"))
        .atTime("2003-07-01T00:05:05Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2003-07-01T00:05:10Z")
        .hasResponse("poll_response_empty.xml");

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainTransferPollMessage_serverApproved() throws Exception {
    // As the losing registrar, create the domain.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // As the winning registrar, request a transfer. Capture the server trid; we'll need it later.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    String response =
        assertThatCommand("domain_transfer_request_1_year.xml")
            .atTime("2001-01-01T00:00:00Z")
            .hasResponse("domain_transfer_response_1_year.xml");
    Matcher matcher = Pattern.compile("<svTRID>(.*)</svTRID>").matcher(response);
    matcher.find();
    String transferRequestTrid = matcher.group(1);
    assertThatLogoutSucceeds();

    // As the losing registrar, read the request poll message, and then ack it.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    String messageId = "24-2001";
    assertThatCommand("poll.xml")
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_response_domain_transfer_request.xml", ImmutableMap.of("ID", messageId));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", messageId))
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_ack_response_empty.xml");

    // Five days in the future, expect a server approval poll message to the loser, and ack it.
    messageId = "23-2001";
    assertThatCommand("poll.xml")
        .atTime("2001-01-06T00:01:00Z")
        .hasResponse(
            "poll_response_domain_transfer_server_approve_loser.xml",
            ImmutableMap.of("ID", messageId));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", messageId))
        .atTime("2001-01-06T00:01:00Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatLogoutSucceeds();

    // Also expect a server approval poll message to the winner, with the transfer request trid.
    messageId = "22-2001";
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("poll.xml")
        .atTime("2001-01-06T00:02:00Z")
        .hasResponse(
            "poll_response_domain_transfer_server_approve_winner.xml",
            ImmutableMap.of("SERVER_TRID", transferRequestTrid, "ID", messageId));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", messageId))
        .atTime("2001-01-06T00:02:00Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testTransfer_autoRenewGraceActive_onlyAtAutomaticTransferTime_getsSubsumed()
      throws Exception {
    // Register the domain as the first registrar.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml", DEFAULT_TRANSFER_RESPONSE_PARMS);
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and verify things.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-02T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer_autorenew.xml");
    assertThatLogoutSucceeds();

    // Log back in as the second registrar and verify transfer details.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    // Verify that domain is in the transfer period now with expiration date still one year out,
    // since the transfer should subsume the autorenew that happened during the transfer window.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-06T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_transfer_period.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-12T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_transfer_complete.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testNameserversTransferWithDomain_successfully() throws Exception {
    // Log in as the first registrar and set up domains with hosts.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml", DEFAULT_TRANSFER_RESPONSE_PARMS);
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and verify domain is pending transfer.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_3_nameservers_pending_transfer.xml");
    assertThatLogoutSucceeds();

    // Log back in as second registrar and verify transfer was successful.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    // Expect transfer complete with all three nameservers on it.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-09T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_3_nameservers_transfer_successful.xml");
    // Verify that host's client ID was set to the new registrar and has the transfer date set.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2002-06-09T00:01:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2002-06-04T00:00:00Z"));
    assertThatLogoutSucceeds();
  }

  @Test
  void testRenewalFails_whenTotalTermExceeds10Years() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Creates domain with 2 year expiration.
    createFakesite();
    // Attempt to renew for 9 years, adding up to a total greater than the allowed max of 10 years.
    assertThatCommand(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "fakesite.example", "EXPDATE", "2002-06-01", "YEARS", "9"))
        .atTime("2000-06-07T00:00:00Z")
        .hasResponse("domain_renew_response_exceeds_max_years.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainDeletionCancelsPendingTransfer() throws Exception {
    // Register the domain as the first registrar.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml", DEFAULT_TRANSFER_RESPONSE_PARMS);
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and delete then restore the domain while the transfer
    // is still pending.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer.xml");
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_action_pending_response.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse("domain_info_response_fakesite_pending_delete.xml");
    assertThatCommand("domain_update_restore_fakesite.xml")
        .atTime("2002-05-30T01:03:00Z")
        .hasResponse("generic_success_response.xml");

    // Expect domain is ok now, not pending delete or transfer, and has been extended by a year from
    // the date of the restore.  (Not from the original expiration date.)
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:04:00Z")
        .hasResponse("domain_info_response_fakesite_restored_ok.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainTransfer_subordinateHost_showsChangeInTransferQuery() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    assertThatLogoutSucceeds();

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Verify that reID is set correctly.
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-02T00:00:00Z")
        .hasResponse("domain_transfer_query_response_fakesite.xml");
    // Verify that status went from 'pending' to 'serverApproved'.
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-08T00:00:00Z")
        .hasResponse("domain_transfer_query_response_completed_fakesite.xml");
    assertThatLogoutSucceeds();
  }

  /**
   * Tests that when a superordinate domain of a host is transferred, and then the host is updated
   * to be subordinate to a different domain, that the host retains the transfer time of the first
   * superordinate domain, not whatever the transfer time from the second domain is.
   */
  @Test
  void testSuccess_lastTransferTime_superordinateDomainTransferFollowedByHostUpdate()
      throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create fakesite.example with subordinate host ns3.fakesite.example
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();
    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    // Verify that the lastTransferTime now reflects the superordinate domain's transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.fakesite.example"))
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatCommand("domain_create_secondsite.xml")
        .atTime("2001-01-08T00:00:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "secondsite.example",
                "CRDATE", "2001-01-08T00:00:00.0Z",
                "EXDATE", "2003-01-08T00:00:00.0Z"));
    // Update the host to be subordinate to a different domain by renaming it to
    // ns3.secondsite.example
    assertThatCommand(
            "host_update_rename_only.xml",
            ImmutableMap.of("oldName", "ns3.fakesite.example", "newName", "ns3.secondsite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // The last transfer time on the host should still be what it was from the transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.secondsite.example"))
        .atTime("2003-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer_and_update.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns3.secondsite.example",
                "TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatLogoutSucceeds();
  }

  /**
   * Tests that when a superordinate domain of a host is transferred, and then the host is updated
   * to be external, that the host retains the transfer time of the first superordinate domain.
   */
  @Test
  void testSuccess_lastTransferTime_superordinateDomainTransferThenHostUpdateToExternal()
      throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create fakesite.example with subordinate host ns3.fakesite.example
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();
    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    // Verify that the lastTransferTime now reflects the superordinate domain's transfer.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2001-01-06T00:00:00.000Z"));
    // Update the host to be external by renaming it to ns3.notarealsite.external
    assertThatCommand(
            "host_update_rename_and_remove_addresses.xml",
            ImmutableMap.of(
                "oldName", "ns3.fakesite.example",
                "newName", "ns3.notarealsite.external"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // The last transfer time on the host should still be what it was from the transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.notarealsite.external"))
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer_and_update_no_addresses.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns3.notarealsite.external",
                "TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatLogoutSucceeds();
  }

  @Test
  void testSuccess_multipartTldsWithSharedSuffixes() throws Exception {
    createTlds("bar.foo.tld", "foo.tld");

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00.000Z"));

    // Create domain example.bar.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "example.bar.foo.tld"))
        .atTime("2000-06-01T00:02:00.001Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.bar.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.foo.tld"))
        .atTime("2000-06-01T00:02:00.002Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00.003Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  void testSuccess_multipartTldsWithSharedPrefixes() throws Exception {
    createTld("tld.foo");

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00.000Z"));

    // Create domain example.tld.foo
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld.foo"))
        .atTime("2000-06-01T00:02:00.001Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld.foo",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00.002Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatLogoutSucceeds();
  }

  /**
   * Test a full launch of start-date sunrise.
   *
   * <p>We show that we can't create during pre-delegation, can only create with an encoded mark
   * during start-date sunrise - which we can then delete "as normal" (no need for a signed mark or
   * anything for delete), and then use "regular" create during general-availability.
   */
  @Test
  void testDomainCreation_startDateSunriseFull() throws Exception {
    // The signed mark is valid between 2022-11-22 and 2027-10-18.
    DateTime sunriseDate = DateTime.parse("2025-09-08T09:09:09Z");
    DateTime gaDate = sunriseDate.plusDays(30);
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            sunriseDate, START_DATE_SUNRISE,
            gaDate, GENERAL_AVAILABILITY));

    assertThatLogin("NewRegistrar", "foo-BAR2")
        .atTime(sunriseDate.minusDays(3))
        .hasSuccessfulLogin();

    createContactsAndHosts();

    // During pre-delegation, any create should fail both with and without mark
    assertThatCommand("domain_create_sunrise_encoded_mark.xml", ImmutableMap.of("SMD", ENCODED_SMD))
        .atTime(sunriseDate.minusDays(2))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(sunriseDate.minusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    // During sunrise, verify that the launch phase must be set to sunrise.
    assertThatCommand(
            "domain_create_start_date_sunrise_encoded_mark_wrong_phase.xml",
            ImmutableMap.of("SMD", ENCODED_SMD))
        .atTime(sunriseDate)
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2306",
                "MSG",
                    "Declared launch extension phase does not match the current registry phase"));

    // During sunrise, create with mark will succeed but without will fail.
    // We also test we can delete without a mark.
    assertThatCommand("domain_create_sunrise_encoded_mark.xml", ImmutableMap.of("SMD", ENCODED_SMD))
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2025-09-09T09:09:09Z",
                "EXDATE", "2026-09-09T09:09:09Z"));

    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1).plusMinutes(1))
        .hasResponse("generic_success_response.xml");

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(sunriseDate.plusDays(2))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase requires a signed mark for registrations"));

    // During general availability, sunrise creates will fail but regular creates succeed
    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(gaDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2306",
                "MSG",
                    "Declared launch extension phase does not match the current registry phase"));

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(gaDate.plusDays(2))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "general.example",
                "CRDATE", "2025-10-10T09:09:09Z",
                "EXDATE", "2027-10-10T09:09:09Z"));

    assertThatLogoutSucceeds();
  }

  /** Test that missing type= argument on launch create works in start-date sunrise. */
  @Test
  void testDomainCreation_startDateSunrise_noType() throws Exception {
    // The signed mark is valid between 2022-11-22 and 2027-10-18.
    DateTime sunriseDate = DateTime.parse("2025-09-08T09:09:09Z");
    DateTime gaDate = sunriseDate.plusDays(30);
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            sunriseDate, START_DATE_SUNRISE,
            gaDate, GENERAL_AVAILABILITY));

    assertThatLogin("NewRegistrar", "foo-BAR2")
        .atTime(sunriseDate.minusDays(3))
        .hasSuccessfulLogin();

    createContactsAndHosts();

    // During start-date sunrise, create with mark will succeed but without will fail.
    // We also test we can delete without a mark.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303",
                "MSG", "The domain with given ID (test-validate.example) doesn't exist."));

    assertThatCommand(
            "domain_create_start_date_sunrise_encoded_mark_no_type.xml",
            ImmutableMap.of("SMD", ENCODED_SMD))
        .atTime(sunriseDate.plusDays(1).plusMinutes(1))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2025-09-09T09:10:09Z",
                "EXDATE", "2026-09-09T09:10:09Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1).plusMinutes(2))
        .hasResponse(
            "domain_info_response_ok_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2025-09-09T09:10:09Z",
                "EXDATE", "2026-09-09T09:10:09Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  void testDomainTransfer_duringAutorenewGrace() throws Exception {
    // Creation date of fakesite: 2000-06-01T00:04:00.0Z
    // Expiration date: 2002-06-01T00:04:00.0Z
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();

    // Domain info before transfer is requested and before autorenew grace period begins
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2001-06-01T00:00:00Z")
        .hasResponse("domain_info_response_before_transfer_and_argp.xml");
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-06-01T00:00:00Z")
        .hasResponse("domain_transfer_query_response_wildcard_not_requested.xml");

    // Domain info before transfer is requested, but after autorenew grace period begins
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-06-02T00:00:00Z")
        .hasResponse("domain_info_response_before_transfer_during_argp.xml");
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2002-06-02T00:00:00Z")
        .hasResponse("domain_transfer_query_response_wildcard_not_requested.xml");

    assertThatLogoutSucceeds();
    assertThatLoginSucceeds("TheRegistrar", "password2");

    // Request the transfer
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-06-05T00:02:00.0Z")
        .hasResponse(
            "domain_transfer_response.xml",
            ImmutableMap.of(
                "REDATE", "2002-06-05T00:02:00Z",
                "ACDATE", "2002-06-10T00:02:00Z",
                "EXDATE", "2003-06-01T00:04:00Z"));

    assertThatLogoutSucceeds();
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");

    // Domain info right after the transfer is requested
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-06-07T00:00:00Z")
        .hasResponse("domain_info_response_during_transfer_during_argp.xml");
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2002-06-07T00:00:00Z")
        .hasResponse(
            "domain_transfer_query_response_wildcard.xml",
            ImmutableMap.of(
                "STATUS", "pending",
                "REDATE", "2002-06-05T00:02:00Z",
                "ACDATE", "2002-06-10T00:02:00Z",
                "EXDATE", "2003-06-01T00:04:00Z"));

    assertThatLogoutSucceeds();
    assertThatLoginSucceeds("TheRegistrar", "password2");

    // Domain info after transfer is implicitly approved, but autorenew grace period is still
    // pending
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-06-11T00:00:00Z")
        .hasResponse("domain_info_response_after_transfer_during_argp.xml");
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2002-06-11T00:00:00Z")
        .hasResponse(
            "domain_transfer_query_response_wildcard.xml",
            ImmutableMap.of(
                "STATUS", "serverApproved",
                "REDATE", "2002-06-05T00:02:00Z",
                "ACDATE", "2002-06-10T00:02:00Z",
                "EXDATE", "2003-06-01T00:04:00Z"));

    // Domain info after the end of autorenew grace period
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-09-11T00:00:00Z")
        .hasResponse("domain_info_response_after_transfer_after_argp.xml");
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2002-09-11T00:00:00Z")
        .hasResponse(
            "domain_transfer_query_response_wildcard.xml",
            ImmutableMap.of(
                "STATUS", "serverApproved",
                "REDATE", "2002-06-05T00:02:00Z",
                "ACDATE", "2002-06-10T00:02:00Z",
                "EXDATE", "2003-06-01T00:04:00Z"));
  }

  @Test
  void testDomainTransfer_queryForServerApproved() throws Exception {
    // Creation date of fakesite: 2000-06-01T00:04:00.0Z
    // Expiration date: 2002-06-01T00:04:00.0Z
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2001-01-01T00:00:00.0Z")
        .hasResponse(
            "domain_transfer_response.xml",
            ImmutableMap.of(
                "REDATE", "2001-01-01T00:00:00Z",
                "ACDATE", "2001-01-06T00:00:00Z",
                "EXDATE", "2003-06-01T00:04:00Z"));
    assertThatLogoutSucceeds();

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Verify that reID is set correctly.
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-03T00:00:00Z")
        .hasResponse("domain_transfer_query_response_fakesite.xml");

    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-08T00:00:00Z")
        .hasResponse("domain_transfer_query_response_completed_fakesite.xml");
  }

  @Test
  void testDomainUpdateBySuperuser_sendsPollMessage() throws Exception {
    setIsSuperuser(false);
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));
    assertThatLogoutSucceeds();

    setIsSuperuser(true);
    assertThatLoginSucceeds("TheRegistrar", "password2");
    // Run domain update that adds server status as superuser
    assertThatCommand("domain_update_server_hold.xml")
        .atTime("2000-06-02T13:00:00Z")
        .hasResponse("generic_success_response.xml");
    assertThatLogoutSucceeds();
    setIsSuperuser(false);

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Verify that the owning registrar now has the correct poll message
    assertThatCommand("poll.xml")
        .atTime("2000-06-02T13:00:01Z")
        .hasResponse("poll_response_server_hold.xml");
    assertThatLogoutSucceeds();
  }
}
