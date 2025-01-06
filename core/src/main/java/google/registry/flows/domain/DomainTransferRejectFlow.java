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

package google.registry.flows.domain;

import static google.registry.flows.FlowUtils.createHistoryEntryId;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyHasPendingTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.createCancelingRecords;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainTransferUtils.createGainingTransferPollMessage;
import static google.registry.flows.domain.DomainTransferUtils.createTransferResponse;
import static google.registry.model.ResourceTransferUtils.denyPendingTransfer;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_NACKED;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REJECT;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.TransferStatus;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that rejects a pending transfer on a domain.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the losing client to
 * reject the transfer request.
 *
 * <p>When the transfer was requested, poll messages and billing events were saved to SQL with
 * timestamps such that they only would become active when the transfer period passed. In this flow,
 * those speculative objects are deleted.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_TRANSFER_REJECT)
public final class DomainTransferRejectFlow implements MutatingFlow {

  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainTransferRejectFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    Domain existingDomain = loadAndVerifyExistence(Domain.class, targetId, now);
    Tld tld = Tld.get(existingDomain.getTld());
    HistoryEntryId domainHistoryId = createHistoryEntryId(existingDomain);
    historyBuilder
        .setRevisionId(domainHistoryId.getRevisionId())
        .setOtherRegistrarId(existingDomain.getTransferData().getGainingRegistrarId());

    verifyOptionalAuthInfo(authInfo, existingDomain);
    verifyHasPendingTransfer(existingDomain);
    verifyResourceOwnership(registrarId, existingDomain);
    if (!isSuperuser) {
      checkAllowedAccessToTld(registrarId, existingDomain.getTld());
    }
    Domain newDomain =
        denyPendingTransfer(existingDomain, TransferStatus.CLIENT_REJECTED, now, registrarId);
    DomainHistory domainHistory = buildDomainHistory(newDomain, tld, now);
    tm().putAll(
            newDomain,
            domainHistory,
            createGainingTransferPollMessage(
                targetId, newDomain.getTransferData(), null, now, domainHistoryId));
    // Reopen the autorenew event and poll message that we closed for the implicit transfer. This
    // may end up recreating the poll message if it was deleted upon the transfer request.
    BillingRecurrence existingBillingRecurrence =
        tm().loadByKey(existingDomain.getAutorenewBillingEvent());
    updateAutorenewRecurrenceEndTime(
        existingDomain, existingBillingRecurrence, END_OF_TIME, domainHistory.getHistoryEntryId());
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    tm().delete(existingDomain.getTransferData().getServerApproveEntities());
    return responseBuilder
        .setResData(createTransferResponse(targetId, newDomain.getTransferData(), null))
        .build();
  }

  private DomainHistory buildDomainHistory(Domain newDomain, Tld tld, DateTime now) {
    ImmutableSet<DomainTransactionRecord> cancelingRecords =
        createCancelingRecords(
            newDomain,
            now,
            tld.getAutomaticTransferLength().plus(tld.getTransferGracePeriodLength()),
            ImmutableSet.of(TRANSFER_SUCCESSFUL));
    return historyBuilder
        .setType(DOMAIN_TRANSFER_REJECT)
        .setDomainTransactionRecords(
            union(
                cancelingRecords,
                DomainTransactionRecord.create(newDomain.getTld(), now, TRANSFER_NACKED, 1)))
        .setDomain(newDomain)
        .build();
  }
}
