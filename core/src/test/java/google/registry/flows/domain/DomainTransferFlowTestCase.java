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
import static google.registry.testing.DatabaseHelper.createBillingEventForTransfer;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.Flow;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.model.EppResource;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for domain transfer flow unit tests.
 *
 * @param <F> the flow type
 * @param <R> the resource type
 */
abstract class DomainTransferFlowTestCase<F extends Flow, R extends EppResource>
    extends ResourceFlowTestCase<F, R> {

  // Transfer is requested on the 6th and expires on the 11th.
  // The "now" of this flow is on the 9th, 3 days in.

  static final DateTime TRANSFER_REQUEST_TIME = DateTime.parse("2000-06-06T22:00:00.0Z");
  static final DateTime TRANSFER_EXPIRATION_TIME =
      TRANSFER_REQUEST_TIME.plus(Tld.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
  private static final Duration TIME_SINCE_REQUEST = Duration.standardDays(3);
  private static final int EXTENDED_REGISTRATION_YEARS = 1;
  private static final DateTime REGISTRATION_EXPIRATION_TIME =
      DateTime.parse("2001-09-08T22:00:00.0Z");
  static final DateTime EXTENDED_REGISTRATION_EXPIRATION_TIME =
      REGISTRATION_EXPIRATION_TIME.plusYears(EXTENDED_REGISTRATION_YEARS);

  protected Domain domain;
  Host subordinateHost;
  private DomainHistory historyEntryDomainCreate;

  DomainTransferFlowTestCase() {
    checkState(!Tld.DEFAULT_TRANSFER_GRACE_PERIOD.isShorterThan(TIME_SINCE_REQUEST));
    clock.setTo(TRANSFER_REQUEST_TIME.plus(TIME_SINCE_REQUEST));
  }

  @BeforeEach
  void beforeEachDomainTransferFlowTestCase() {
    // Registrar ClientZ is used in tests that need another registrar that definitely doesn't own
    // the resources in question.
    persistResource(
        JpaTransactionManagerExtension.makeRegistrar1()
            .asBuilder()
            .setRegistrarId("ClientZ")
            .build());
  }

  static Domain persistWithPendingTransfer(Domain domain) {
    return persistDomainWithPendingTransfer(
        domain,
        TRANSFER_REQUEST_TIME,
        TRANSFER_EXPIRATION_TIME,
        EXTENDED_REGISTRATION_EXPIRATION_TIME);
  }

  /** Adds a domain with no pending transfer on it. */
  void setupDomain(String label, String tld) {
    createTld(tld);
    domain =
        persistDomainWithDependentResources(
            label,
            tld,
            clock.nowUtc(),
            DateTime.parse("1999-04-03T22:00:00.0Z"),
            REGISTRATION_EXPIRATION_TIME);
    subordinateHost =
        persistResource(
            new Host.Builder()
                .setRepoId("2-".concat(Ascii.toUpperCase(tld)))
                .setHostName("ns1." + label + "." + tld)
                .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
                .setCreationRegistrarId("TheRegistrar")
                .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
                .setSuperordinateDomain(domain.createVKey())
                .build());
    domain =
        persistResource(
            domain.asBuilder().addSubordinateHost(subordinateHost.getHostName()).build());
    historyEntryDomainCreate =
        getOnlyHistoryEntryOfType(domain, DOMAIN_CREATE, DomainHistory.class);
  }

  BillingEvent getBillingEventForImplicitTransfer() {
    DomainHistory historyEntry =
        getOnlyHistoryEntryOfType(
            domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST, DomainHistory.class);
    return createBillingEventForTransfer(
        domain, historyEntry, TRANSFER_REQUEST_TIME, TRANSFER_EXPIRATION_TIME);
  }

  /** Get the autorenew event that the losing client will have after a SERVER_APPROVED transfer. */
  BillingRecurrence getLosingClientAutorenewEvent() {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setRegistrarId("TheRegistrar")
        .setEventTime(REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(TRANSFER_EXPIRATION_TIME)
        .setDomainHistory(historyEntryDomainCreate)
        .build();
  }

  /** Get the autorenew event that the gaining client will have after a SERVER_APPROVED transfer. */
  BillingRecurrence getGainingClientAutorenewEvent() {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setRegistrarId("NewRegistrar")
        .setEventTime(EXTENDED_REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistory(
            getOnlyHistoryEntryOfType(
                domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST, DomainHistory.class))
        .build();
  }

  void assertTransferFailed(Domain domain, TransferStatus status, TransferData oldTransferData) {
    assertAboutDomains()
        .that(domain)
        .doesNotHaveStatusValue(StatusValue.PENDING_TRANSFER)
        .and()
        .hasCurrentSponsorRegistrarId("TheRegistrar");
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
  void setupDomainWithPendingTransfer(String label, String tld) {
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
