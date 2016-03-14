// Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.domain.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.model.EppResourceUtils.loadByUniqueId;
import static com.google.domain.registry.testing.DatastoreHelper.assertBillingEventsForResource;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.deleteResource;
import static com.google.domain.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static com.google.domain.registry.testing.DatastoreHelper.getOnlyPollMessage;
import static com.google.domain.registry.testing.DatastoreHelper.getPollMessages;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.DomainResourceSubject.assertAboutDomains;
import static com.google.domain.registry.testing.GenericEppResourceSubject.assertAboutEppResources;
import static com.google.domain.registry.util.DateTimeUtils.START_OF_TIME;
import static java.util.Arrays.asList;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.domain.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import com.google.domain.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import com.google.domain.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException;
import com.google.domain.registry.flows.ResourceMutatePendingTransferFlow.NotPendingTransferException;
import com.google.domain.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.billing.BillingEvent;
import com.google.domain.registry.model.billing.BillingEvent.Cancellation;
import com.google.domain.registry.model.billing.BillingEvent.Cancellation.Builder;
import com.google.domain.registry.model.billing.BillingEvent.Reason;
import com.google.domain.registry.model.contact.ContactAuthInfo;
import com.google.domain.registry.model.domain.DomainAuthInfo;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.domain.GracePeriod;
import com.google.domain.registry.model.domain.rgp.GracePeriodStatus;
import com.google.domain.registry.model.eppcommon.AuthInfo.PasswordAuth;
import com.google.domain.registry.model.eppcommon.StatusValue;
import com.google.domain.registry.model.eppcommon.Trid;
import com.google.domain.registry.model.host.HostResource;
import com.google.domain.registry.model.poll.PendingActionNotificationResponse;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.registrar.Registrar;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.transfer.TransferResponse;
import com.google.domain.registry.model.transfer.TransferStatus;

import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainTransferApproveFlow}. */
public class DomainTransferApproveFlowTest
    extends DomainTransferFlowTestCase<DomainTransferApproveFlow, DomainResource> {

  @Before
  public void setUp() throws Exception {
    setEppInput("domain_transfer_approve.xml");
    // Change the registry so that the renew price changes a day minus 1 millisecond before the
    // transfer (right after there will be an autorenew in the test case that has one) and then
    // again a millisecond after the transfer request time. These changes help us ensure that the
    // flows are using prices from the moment of transfer request (or autorenew) and not from the
    // moment that the transfer is approved.
    createTld("tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 1))
                    .put(clock.nowUtc().minusDays(1).plusMillis(1), Money.of(USD, 22))
                    .put(TRANSFER_REQUEST_TIME.plusMillis(1), Money.of(USD, 333))
                    .build())
            .build());
    setClientIdForFlow("TheRegistrar");
    setupDomainWithPendingTransfer();
    clock.advanceOneMilli();
  }

  private void assertTransferApproved(EppResource resource) {
    assertAboutEppResources().that(resource)
        .hasTransferStatus(TransferStatus.CLIENT_APPROVED).and()
        .hasCurrentSponsorClientId("NewRegistrar").and()
        .hasLastTransferTime(clock.nowUtc()).and()
        .hasPendingTransferExpirationTime(clock.nowUtc()).and()
        .doesNotHaveStatusValue(StatusValue.PENDING_TRANSFER);
  }

  private void setEppLoader(String commandFilename) {
    setEppInput(commandFilename);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
  }

  /**
   * Runs a successful test, with the expectedCancellationBillingEvents parameter containing a list
   * of billing event builders that will be filled out with the correct HistoryEntry parent as it is
   * created during the execution of this test.
   */
  private void doSuccessfulTest(
      String tld,
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      int expectedYearsToCharge,
      BillingEvent.Cancellation.Builder... expectedCancellationBillingEvents) throws Exception {
    setEppLoader(commandFilename);
    Registry registry = Registry.get(tld);
    // Make sure the implicit billing event is there; it will be deleted by the flow.
    // We also expect to see autorenew events for the gaining and losing registrars.
    assertBillingEventsForResource(
        domain,
        getBillingEventForImplicitTransfer(),
        getGainingClientAutorenewEvent(),
        getLosingClientAutorenewEvent());
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages(domain, "NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages(domain, "TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlowAssertResponse(readFile(expectedXmlFilename));
    // Transfer should have succeeded. Verify correct fields were set.
    domain = reloadResourceByUniqueId();
    assertAboutDomains().that(domain).hasOneHistoryEntryEachOfTypes(
        HistoryEntry.Type.DOMAIN_CREATE,
        HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST,
        HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE);
    final HistoryEntry historyEntryTransferApproved =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE);
    assertTransferApproved(domain);
    assertAboutDomains().that(domain).hasRegistrationExpirationTime(expectedExpirationTime);
    assertThat(domain.getAutorenewBillingEvent().get().getEventTime())
        .isEqualTo(expectedExpirationTime);
    assertTransferApproved(reloadResourceAndCloneAtTime(subordinateHost, clock.nowUtc()));
    // We expect three billing events: one for the transfer, a closed autorenew for the losing
    // client and an open autorenew for the gaining client that begins at the new expiration time.
    BillingEvent.OneTime transferBillingEvent = new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setEventTime(clock.nowUtc())
        .setBillingTime(clock.nowUtc().plus(registry.getTransferGracePeriodLength()))
        .setClientId("NewRegistrar")
        .setCost(Money.of(USD, 11).multipliedBy(expectedYearsToCharge))
        .setPeriodYears(expectedYearsToCharge)
        .setParent(historyEntryTransferApproved)
        .build();
    assertBillingEventsForResource(
        domain,
        FluentIterable.from(asList(expectedCancellationBillingEvents))
            .transform(new Function<BillingEvent.Cancellation.Builder, BillingEvent>() {
              @Override
              public Cancellation apply(Builder builder) {
                return builder.setParent(historyEntryTransferApproved).build();
              }})
            .append(
                transferBillingEvent,
                getLosingClientAutorenewEvent().asBuilder()
                    .setRecurrenceEndTime(clock.nowUtc())
                    .build(),
                getGainingClientAutorenewEvent().asBuilder()
                    .setEventTime(domain.getRegistrationExpirationTime())
                    .setParent(historyEntryTransferApproved)
                    .build())
            .toArray(BillingEvent.class));
    // There should be a grace period for the new transfer billing event.
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.TRANSFER,
                clock.nowUtc().plus(registry.getTransferGracePeriodLength()),
                "NewRegistrar",
                null),
            transferBillingEvent));
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages(domain, "TheRegistrar", clock.nowUtc().plusMonths(1))).isEmpty();

    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar, as well as one at the domain's
    // autorenew time.
    assertThat(getPollMessages(domain, "NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages(domain, "NewRegistrar", domain.getRegistrationExpirationTime()))
        .hasSize(2);

    PollMessage gainingTransferPollMessage =
        getOnlyPollMessage(domain, "NewRegistrar", clock.nowUtc(), PollMessage.OneTime.class);
    PollMessage gainingAutorenewPollMessage = getOnlyPollMessage(
        domain,
        "NewRegistrar",
        domain.getRegistrationExpirationTime(),
        PollMessage.Autorenew.class);
    assertThat(gainingTransferPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(gainingAutorenewPollMessage.getEventTime())
        .isEqualTo(domain.getRegistrationExpirationTime());
    assertThat(
        Iterables.getOnlyElement(FluentIterable
            .from(gainingTransferPollMessage.getResponseData())
            .filter(TransferResponse.class))
                .getTransferStatus())
                .isEqualTo(TransferStatus.CLIENT_APPROVED);
    PendingActionNotificationResponse panData = Iterables.getOnlyElement(FluentIterable
        .from(gainingTransferPollMessage.getResponseData())
        .filter(PendingActionNotificationResponse.class));
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isTrue();

    // After the expected grace time, the grace period should be gone.
    assertThat(
        domain.cloneProjectedAtTime(clock.nowUtc().plus(registry.getTransferGracePeriodLength()))
            .getGracePeriods()).isEmpty();
  }

  private void doSuccessfulTest(String tld, String commandFilename, String expectedXmlFilename)
      throws Exception {
    clock.advanceOneMilli();
    doSuccessfulTest(
        tld,
        commandFilename,
        expectedXmlFilename,
        domain.getRegistrationExpirationTime().plusYears(1),
        1);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppLoader(commandFilename);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  public void testDryRun() throws Exception {
    setEppLoader("domain_transfer_approve.xml");
    dryRunFlowAssertResponse(readFile("domain_transfer_approve_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
  }

  @Test
  public void testSuccess_nonDefaultTransferGracePeriod() throws Exception {
    // We have to set up a new domain in a different TLD so that the billing event will be persisted
    // with the new transfer grace period in mind.
    createTld("net");
    persistResource(
        Registry.get("net")
            .asBuilder()
            .setTransferGracePeriodLength(Duration.standardMinutes(10))
            .build());
    setupDomainWithPendingTransfer("net");
    doSuccessfulTest(
        "net",
        "domain_transfer_approve_net.xml",
        "domain_transfer_approve_response_net.xml");
  }

  @Test
  public void testSuccess_lastTransferTime_reflectedOnSubordinateHost() throws Exception {
    domain = reloadResourceByUniqueId();
    // Set an older last transfer time on the subordinate host.
    subordinateHost = persistResource(
        subordinateHost.asBuilder()
            .setLastTransferTime(DateTime.parse("2000-02-03T22:00:00.0Z"))
            .build());

    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
    subordinateHost = loadByUniqueId(
        HostResource.class, subordinateHost.getFullyQualifiedHostName(), clock.nowUtc());
    // Verify that the host's last transfer time is now that of when the superordinate domain was
    // transferred.
    assertThat(subordinateHost.getLastTransferTime()).isEqualTo(clock.nowUtc());
  }

  @Test
  public void testSuccess_lastTransferTime_overridesExistingOnSubordinateHost() throws Exception {
    domain = reloadResourceByUniqueId();
    // Set an older last transfer time on the subordinate host.
    subordinateHost = persistResource(
        subordinateHost.asBuilder()
            .setLastTransferTime(DateTime.parse("2000-02-03T22:00:00.0Z"))
            .setLastSuperordinateChange(DateTime.parse("2000-03-03T22:00:00.0Z"))
            .build());

    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
    subordinateHost = loadByUniqueId(
        HostResource.class, subordinateHost.getFullyQualifiedHostName(), clock.nowUtc());
    // Verify that the host's last transfer time is now that of when the superordinate domain was
    // transferred.
    assertThat(subordinateHost.getLastTransferTime()).isEqualTo(clock.nowUtc());
  }

  @Test
  public void testSuccess_lastTransferTime_overridesExistingOnSubordinateHostWithNullTransferTime()
      throws Exception {
    domain = reloadResourceByUniqueId();
    // Set an older last transfer time on the subordinate host.
    subordinateHost = persistResource(
        subordinateHost.asBuilder()
            .setLastTransferTime(null)
            .setLastSuperordinateChange(DateTime.parse("2000-03-03T22:00:00.0Z"))
            .build());

    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
    subordinateHost = loadByUniqueId(
        HostResource.class, subordinateHost.getFullyQualifiedHostName(), clock.nowUtc());
    // Verify that the host's last transfer time is now that of when the superordinate domain was
    // transferred.
    assertThat(subordinateHost.getLastTransferTime()).isEqualTo(clock.nowUtc());
  }

  @Test
  public void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_domain_authinfo.xml",
        "domain_transfer_approve_response.xml");
  }

  @Test
  public void testSuccess_contactAuthInfo() throws Exception {
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_contact_authinfo.xml",
        "domain_transfer_approve_response.xml");
  }

  @Test
  public void testSuccess_autorenewBeforeTransfer() throws Exception {
    DomainResource domain = reloadResourceByUniqueId();
    DateTime oldExpirationTime = clock.nowUtc().minusDays(1);
    persistResource(domain.asBuilder()
        .setRegistrationExpirationTime(oldExpirationTime)
        .setTransferData(domain.getTransferData().asBuilder()
            .setExtendedRegistrationYears(2)
            .build())
        .build());
    // The autorenew should be subsumed into the transfer resulting in 2 years of renewal in total.
    clock.advanceOneMilli();
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_domain_authinfo.xml",
        "domain_transfer_approve_response_autorenew.xml",
        oldExpirationTime.plusYears(2),
        2,
        // Expect the grace period for autorenew to be cancelled.
        new BillingEvent.Cancellation.Builder()
            .setReason(Reason.AUTO_RENEW)
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(clock.nowUtc())  // The cancellation happens at the moment of transfer.
            .setBillingTime(
                oldExpirationTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()))
            .setRecurringEventRef(domain.getAutorenewBillingEvent()));
  }

  @Test
  public void testFailure_badContactPassword() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    // Change the contact's password so it does not match the password in the file.
    contact = persistResource(
        contact.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("badpassword")))
            .build());
    doFailingTest("domain_transfer_approve_contact_authinfo.xml");
  }

  @Test
  public void testFailure_badDomainPassword() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    // Change the domain's password so it does not match the password in the file.
    domain = persistResource(domain.asBuilder()
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("badpassword")))
        .build());
    doFailingTest("domain_transfer_approve_domain_authinfo.xml");
  }

  @Test
  public void testFailure_neverBeenTransferred() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(null);
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_clientApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doFailingTest("domain_transfer_approve.xml");
  }

 @Test
  public void testFailure_clientRejected() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doFailingTest("domain_transfer_approve.xml");
  }

 @Test
  public void testFailure_clientCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_serverApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_serverCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_gainingClient() throws Exception {
    thrown.expect(ResourceNotOwnedException.class);
    setClientIdForFlow("NewRegistrar");
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_unrelatedClient() throws Exception {
    thrown.expect(ResourceNotOwnedException.class);
    setClientIdForFlow("ClientZ");
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_deletedDomain() throws Exception {
    thrown.expect(ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    domain = persistResource(
        domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_nonexistentDomain() throws Exception {
    thrown.expect(ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    deleteResource(domain);
    doFailingTest("domain_transfer_approve.xml");
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    thrown.expect(NotAuthorizedForTldException.class);
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.<String>of())
            .build());
    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
  }

  // NB: No need to test pending delete status since pending transfers will get cancelled upon
  // entering pending delete phase. So it's already handled in that test case.
}

