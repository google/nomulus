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

package google.registry.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import org.joda.time.DateTime;

/** Static utility functions for domain transfers. */
public final class ResourceTransferUtils {

  private ResourceTransferUtils() {}

  /** Statuses for which an exDate should be added to transfer responses. */
  private static final ImmutableSet<TransferStatus> ADD_EXDATE_STATUSES = Sets.immutableEnumSet(
      TransferStatus.PENDING, TransferStatus.CLIENT_APPROVED, TransferStatus.SERVER_APPROVED);

  /** Create a transfer response using the domain and the specified {@link DomainTransferData}. */
  public static TransferResponse createTransferResponse(
      Domain domain, DomainTransferData transferData) {
    return new DomainTransferResponse.Builder()
        .setDomainName(domain.getForeignKey())
        .setExtendedRegistrationExpirationTime(
            ADD_EXDATE_STATUSES.contains(transferData.getTransferStatus())
                ? transferData.getTransferredRegistrationExpirationTime()
                : null)
        .setGainingRegistrarId(transferData.getGainingRegistrarId())
        .setLosingRegistrarId(transferData.getLosingRegistrarId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus())
        .build();
  }

  /**
   * Create a pending action notification response indicating the resolution of a transfer.
   *
   * <p>The returned object will use the trid of the domain's last transfer request, and the
   * specified status and date.
   */
  public static PendingActionNotificationResponse createPendingTransferNotificationResponse(
      Domain domain, Trid transferRequestTrid, boolean actionResult, DateTime processedDate) {
    return DomainPendingActionNotificationResponse.create(
        domain.getDomainName(), actionResult, transferRequestTrid, processedDate);
  }

  /** If there is a transfer out, delete the server-approve entities and enqueue a poll message. */
  public static void handlePendingTransferOnDelete(
      Domain domain, Domain newDomain, DateTime now, HistoryEntry historyEntry) {
    if (!domain.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      return;
    }
    DomainTransferData oldTransferData = domain.getTransferData();
    tm().delete(oldTransferData.getServerApproveEntities());
    tm().put(
            new PollMessage.OneTime.Builder()
                .setRegistrarId(oldTransferData.getGainingRegistrarId())
                .setEventTime(now)
                .setMsg(TransferStatus.SERVER_CANCELLED.getMessage())
                .setResponseData(
                    ImmutableList.of(
                        createTransferResponse(newDomain, newDomain.getTransferData()),
                        createPendingTransferNotificationResponse(
                            domain, oldTransferData.getTransferRequestTrid(), false, now)))
                .setHistoryEntry(historyEntry)
                .build());
  }

  /**
   * Turn a domain into a builder with its pending transfer resolved.
   *
   * <p>This removes the {@link StatusValue#PENDING_TRANSFER} status, sets the {@link
   * TransferStatus}, clears all the server-approve fields on the {@link DomainTransferData}, and
   * sets the expiration time of the last pending transfer to now.
   */
  private static Domain.Builder resolvePendingTransfer(
      Domain domain, TransferStatus transferStatus, DateTime now) {
    checkArgument(
        domain.getStatusValues().contains(StatusValue.PENDING_TRANSFER),
        "Domain is not in pending transfer status.");
    checkArgument(!domain.getTransferData().isEmpty(), "No old transfer data to resolve.");

    return domain
        .asBuilder()
        .removeStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(
            domain
                .getTransferData()
                .copyConstantFieldsToBuilder()
                .setTransferStatus(transferStatus)
                .setPendingTransferExpirationTime(checkNotNull(now))
                .build());
  }

  /**
   * Resolve a pending transfer by awarding it to the gaining client.
   *
   * <p>This removes the {@link StatusValue#PENDING_TRANSFER} status, sets the {@link
   * TransferStatus}, clears all the server-approve fields on the {@link DomainTransferData}, sets
   * the new client id, and sets the last transfer time and the expiration time of the last pending
   * transfer to now.
   */
  public static Domain approvePendingTransfer(
      Domain domain, TransferStatus transferStatus, DateTime now) {
    checkArgument(transferStatus.isApproved(), "Not an approval transfer status");
    Domain.Builder builder = resolvePendingTransfer(domain, transferStatus, now);
    return builder
        .setLastTransferTime(now)
        .setPersistedCurrentSponsorRegistrarId(domain.getTransferData().getGainingRegistrarId())
        .build();
  }

  /**
   * Resolve a pending transfer by denying it.
   *
   * <p>This removes the {@link StatusValue#PENDING_TRANSFER} status, sets the {@link
   * TransferStatus}, clears all the server-approve fields on the {@link DomainTransferData}, sets
   * the expiration time of the last pending transfer to now, sets the last EPP update time to now,
   * and sets the last EPP update client id to the given client id.
   */
  public static Domain denyPendingTransfer(
      Domain domain, TransferStatus transferStatus, DateTime now, String lastEppUpdateRegistrarId) {
    checkArgument(transferStatus.isDenied(), "Not a denial transfer status");
    return resolvePendingTransfer(domain, transferStatus, now)
        .setLastEppUpdateTime(now)
        .setLastEppUpdateRegistrarId(lastEppUpdateRegistrarId)
        .build();
  }
}
