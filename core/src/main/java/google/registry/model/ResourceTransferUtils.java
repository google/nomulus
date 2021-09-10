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
import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.EppResource.BuilderWithTransferData;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import org.joda.time.DateTime;

/** Static utility functions for resource transfers. */
public final class ResourceTransferUtils {

  private ResourceTransferUtils() {}

  /** Statuses for which an exDate should be added to transfer responses. */
  private static final ImmutableSet<TransferStatus> ADD_EXDATE_STATUSES = Sets.immutableEnumSet(
      TransferStatus.PENDING, TransferStatus.CLIENT_APPROVED, TransferStatus.SERVER_APPROVED);

  /**
   * Create a transfer response using the id and type of this resource and the specified {@link
   * TransferData}.
   */
  public static TransferResponse createTransferResponse(
      EppResource eppResource, TransferData transferData) {
    assertIsContactOrDomain(eppResource);
    TransferResponse.Builder<? extends TransferResponse, ?> builder;
    if (eppResource instanceof ContactResource) {
      builder = new ContactTransferResponse.Builder().setContactId(eppResource.getForeignKey());
    } else {
      DomainTransferData domainTransferData = (DomainTransferData) transferData;
      builder =
          new DomainTransferResponse.Builder()
              .setFullyQualifiedDomainName(eppResource.getForeignKey())
              .setExtendedRegistrationExpirationTime(
                  ADD_EXDATE_STATUSES.contains(domainTransferData.getTransferStatus())
                      ? domainTransferData.getTransferredRegistrationExpirationTime()
                      : null);
    }
    builder
        .setGainingRegistrarId(transferData.getGainingRegistrarId())
        .setLosingRegistrarId(transferData.getLosingRegistrarId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus());
    return builder.build();
  }

  /**
   * Create a pending action notification response indicating the resolution of a transfer.
   *
   * <p>The returned object will use the id and type of this resource, the trid of the resource's
   * last transfer request, and the specified status and date.
   */
  public static PendingActionNotificationResponse createPendingTransferNotificationResponse(
      EppResource eppResource,
      Trid transferRequestTrid,
      boolean actionResult,
      DateTime processedDate) {
    assertIsContactOrDomain(eppResource);
    return eppResource instanceof ContactResource
        ? ContactPendingActionNotificationResponse.create(
            eppResource.getForeignKey(), actionResult, transferRequestTrid, processedDate)
        : DomainPendingActionNotificationResponse.create(
            eppResource.getForeignKey(), actionResult, transferRequestTrid, processedDate);
  }

  private static void assertIsContactOrDomain(EppResource eppResource) {
    checkState(eppResource instanceof ContactResource || eppResource instanceof DomainBase);
  }

  /** Update the relevant {@link ForeignKeyIndex} to cache the new deletion time. */
  public static <R extends EppResource> void updateForeignKeyIndexDeletionTime(R resource) {
    if (resource instanceof ForeignKeyedEppResource) {
      tm().insert(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
    }
  }

  /** If there is a transfer out, delete the server-approve entities and enqueue a poll message. */
  public static <R extends EppResource & ResourceWithTransferData>
      void handlePendingTransferOnDelete(
          R resource, R newResource, DateTime now, HistoryEntry historyEntry) {
    if (resource.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      TransferData oldTransferData = resource.getTransferData();
      tm().delete(oldTransferData.getServerApproveEntities());
      tm().put(
              new PollMessage.OneTime.Builder()
                  .setRegistrarId(oldTransferData.getGainingRegistrarId())
                  .setEventTime(now)
                  .setMsg(TransferStatus.SERVER_CANCELLED.getMessage())
                  .setResponseData(
                      ImmutableList.of(
                          createTransferResponse(newResource, newResource.getTransferData()),
                          createPendingTransferNotificationResponse(
                              resource, oldTransferData.getTransferRequestTrid(), false, now)))
                  .setParent(historyEntry)
                  .build());
    }
  }

  /**
   * Turn a resource into a builder with its pending transfer resolved.
   *
   * <p>This removes the {@link StatusValue#PENDING_TRANSFER} status, sets the {@link
   * TransferStatus}, clears all the server-approve fields on the {@link TransferData}, and sets the
   * expiration time of the last pending transfer to now.
   */
  private static <
          R extends EppResource & ResourceWithTransferData,
          B extends EppResource.Builder<R, B> & BuilderWithTransferData<TransferData, B>>
      B resolvePendingTransfer(R resource, TransferStatus transferStatus, DateTime now) {
    checkArgument(
        resource.getStatusValues().contains(StatusValue.PENDING_TRANSFER),
        "Resource is not in pending transfer status.");
    checkArgument(!resource.getTransferData().isEmpty(), "No old transfer data to resolve.");
    @SuppressWarnings("unchecked")
    B builder = (B) resource.asBuilder();

    return builder
        .removeStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(
            (TransferData)
                resource
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
   * TransferStatus}, clears all the server-approve fields on the {@link TransferData}, sets the new
   * client id, and sets the last transfer time and the expiration time of the last pending transfer
   * to now.
   */
  public static <
          R extends EppResource & ResourceWithTransferData,
          B extends EppResource.Builder<R, B> & BuilderWithTransferData<TransferData, B>>
      R approvePendingTransfer(R resource, TransferStatus transferStatus, DateTime now) {
    checkArgument(transferStatus.isApproved(), "Not an approval transfer status");
    B builder = resolvePendingTransfer(resource, transferStatus, now);
    return builder
        .setLastTransferTime(now)
        .setPersistedCurrentSponsorRegistrarId(resource.getTransferData().getGainingRegistrarId())
        .build();
  }

  /**
   * Resolve a pending transfer by denying it.
   *
   * <p>This removes the {@link StatusValue#PENDING_TRANSFER} status, sets the {@link
   * TransferStatus}, clears all the server-approve fields on the {@link TransferData}, sets the
   * expiration time of the last pending transfer to now, sets the last EPP update time to now, and
   * sets the last EPP update client id to the given client id.
   */
  public static <R extends EppResource & ResourceWithTransferData> R denyPendingTransfer(
      R resource, TransferStatus transferStatus, DateTime now, String lastEppUpdateClientId) {
    checkArgument(transferStatus.isDenied(), "Not a denial transfer status");
    return resolvePendingTransfer(resource, transferStatus, now)
        .setLastEppUpdateTime(now)
        .setLastEppUpdateRegistrarId(lastEppUpdateClientId)
        .build();
  }
}
