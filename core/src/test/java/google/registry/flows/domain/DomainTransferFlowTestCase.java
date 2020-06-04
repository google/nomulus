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

package google.registry.flows.domain;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.testing.DatastoreHelper.createBillingEventForTransfer;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatastoreHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.Flow;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.model.EppResource;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;

/**
 * Base class for domain transfer flow unit tests.
 *
 * @param <F> the flow type
 * @param <R> the resource type
 */
public class DomainTransferFlowTestCase<F extends Flow, R extends EppResource>
    extends ResourceFlowTestCase<F, R>{

  // Transfer is requested on the 6th and expires on the 11th.
  // The "now" of this flow is on the 9th, 3 days in.

  protected static final DateTime TRANSFER_REQUEST_TIME = DateTime.parse("2000-06-06T22:00:00.0Z");
  protected static final DateTime TRANSFER_EXPIRATION_TIME =
      TRANSFER_REQUEST_TIME.plus(Registry.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
  protected static final Duration TIME_SINCE_REQUEST = Duration.standardDays(3);
  protected static final int EXTENDED_REGISTRATION_YEARS = 1;
  protected static final DateTime REGISTRATION_EXPIRATION_TIME =
      DateTime.parse("2001-09-08T22:00:00.0Z");
  protected static final DateTime EXTENDED_REGISTRATION_EXPIRATION_TIME =
      REGISTRATION_EXPIRATION_TIME.plusYears(EXTENDED_REGISTRATION_YEARS);

  protected ContactResource contact;
  protected DomainBase domain;
  protected HostResource subordinateHost;
  protected HistoryEntry historyEntryDomainCreate;

  public DomainTransferFlowTestCase() {
    checkState(!Registry.DEFAULT_TRANSFER_GRACE_PERIOD.isShorterThan(TIME_SINCE_REQUEST));
    clock.setTo(TRANSFER_REQUEST_TIME.plus(TIME_SINCE_REQUEST));
  }

  @Before
  public void makeClientZ() {
    // Registrar ClientZ is used in tests that need another registrar that definitely doesn't own
    // the resources in question.
    persistResource(
        AppEngineRule.makeRegistrar1().asBuilder().setClientId("ClientZ").build());
  }

  static DomainBase persistWithPendingTransfer(DomainBase domain) {
    return persistDomainWithPendingTransfer(
        domain,
        TRANSFER_REQUEST_TIME,
        TRANSFER_EXPIRATION_TIME,
        EXTENDED_REGISTRATION_EXPIRATION_TIME);
  }

  /** Adds a domain with no pending transfer on it. */
  protected void setupDomain(String label, String tld) {
    createTld(tld);
    contact = persistActiveContact("jd1234");
    domain =
        persistDomainWithDependentResources(
            label,
            tld,
            contact,
            clock.nowUtc(),
            DateTime.parse("1999-04-03T22:00:00.0Z"),
            REGISTRATION_EXPIRATION_TIME);
    subordinateHost = persistResource(
        new HostResource.Builder()
            .setRepoId("2-".concat(Ascii.toUpperCase(tld)))
            .setFullyQualifiedHostName("ns1." + label + "." + tld)
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setCreationClientId("TheRegistrar")
            .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
            .setSuperordinateDomain(domain.createVKey())
            .build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .addSubordinateHost(subordinateHost.getFullyQualifiedHostName())
                .build());
    historyEntryDomainCreate = getOnlyHistoryEntryOfType(domain, DOMAIN_CREATE);
  }

  protected BillingEvent.OneTime getBillingEventForImplicitTransfer() {
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST);
    return createBillingEventForTransfer(
        domain,
        historyEntry,
        TRANSFER_REQUEST_TIME,
        TRANSFER_EXPIRATION_TIME);
  }

  /** Get the autorenew event that the losing client will have after a SERVER_APPROVED transfer. */
  protected BillingEvent.Recurring getLosingClientAutorenewEvent() {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId("TheRegistrar")
        .setEventTime(REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(TRANSFER_EXPIRATION_TIME)
        .setParent(historyEntryDomainCreate)
        .build();
  }

  /** Get the autorenew event that the gaining client will have after a SERVER_APPROVED transfer. */
  protected BillingEvent.Recurring getGainingClientAutorenewEvent() {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId("NewRegistrar")
        .setEventTime(EXTENDED_REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST))
        .build();
  }

  protected void assertTransferFailed(
      DomainBase domain, TransferStatus status, TransferData oldTransferData) {
    assertAboutDomains().that(domain)
        .doesNotHaveStatusValue(StatusValue.PENDING_TRANSFER).and()
        .hasCurrentSponsorClientId("TheRegistrar");
    // The domain TransferData should reflect the failed transfer as we expect, with
    // all the speculative server-approve fields nulled out.
    assertThat(domain.getTransferData())
        .isEqualTo(
            oldTransferData.copyConstantFieldsToBuilder()
                .setTransferStatus(status)
                .setPendingTransferExpirationTime(clock.nowUtc())
                .build());
  }

  /** Adds a domain that has a pending transfer on it from TheRegistrar to NewRegistrar. */
  protected void setupDomainWithPendingTransfer(String label, String tld) {
    setupDomain(label, tld);
    domain = persistWithPendingTransfer(domain);
  }

  /** Changes the transfer status on the persisted domain. */
  protected void changeTransferStatus(TransferStatus transferStatus) {
    domain = persistResource(domain.asBuilder().setTransferData(
        domain.getTransferData().asBuilder().setTransferStatus(transferStatus).build()).build());
    clock.advanceOneMilli();
  }
}
