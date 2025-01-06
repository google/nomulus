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

package google.registry.model.transfer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.VKey;
import google.registry.util.NullIgnoringCollectionBuilder;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Common transfer data for {@link EppResource} types. Only applies to domains and contacts; hosts
 * are implicitly transferred with their superordinate domain.
 */
@MappedSuperclass
public abstract class TransferData extends BaseTransferObject implements Buildable {

  /** The transaction id of the most recent transfer request (or null if there never was one). */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "serverTransactionId",
        column = @Column(name = "transfer_server_txn_id")),
    @AttributeOverride(
        name = "clientTransactionId",
        column = @Column(name = "transfer_client_txn_id"))
  })
  Trid transferRequestTrid;

  @Column(name = "transfer_repo_id")
  String repoId;

  @Column(name = "transfer_history_entry_id")
  Long historyEntryId;

  // The pollMessageId1 and pollMessageId2 are used to store the IDs for gaining and losing poll
  // messages in Cloud SQL.
  //
  // In addition, there may be a third poll message for the autorenew poll message on domain
  // transfer if applicable.
  @Column(name = "transfer_poll_message_id_1")
  Long pollMessageId1;

  @Column(name = "transfer_poll_message_id_2")
  Long pollMessageId2;

  @Column(name = "transfer_poll_message_id_3")
  Long pollMessageId3;

  public abstract boolean isEmpty();

  public Long getHistoryEntryId() {
    return historyEntryId;
  }

  @Nullable
  public Trid getTransferRequestTrid() {
    return transferRequestTrid;
  }

  public ImmutableSet<VKey<? extends TransferServerApproveEntity>> getServerApproveEntities() {
    return NullIgnoringCollectionBuilder.create(
            new ImmutableSet.Builder<VKey<? extends TransferServerApproveEntity>>())
        .add(pollMessageId1 != null ? VKey.create(PollMessage.class, pollMessageId1) : null)
        .add(pollMessageId2 != null ? VKey.create(PollMessage.class, pollMessageId2) : null)
        .add(pollMessageId3 != null ? VKey.create(PollMessage.class, pollMessageId3) : null)
        .getBuilder()
        .build();
  }

  @Override
  public abstract Builder<?, ?> asBuilder();

  protected abstract Builder<?, ?> createEmptyBuilder();

  /**
   * Returns a fresh Builder populated only with the constant fields of this TransferData, i.e.
   * those that are fixed and unchanging throughout the transfer process.
   *
   * <p>These fields are:
   *
   * <ul>
   *   <li>transferRequestTrid
   *   <li>transferRequestTime
   *   <li>gainingClientId
   *   <li>losingClientId
   *   <li>transferPeriod
   * </ul>
   */
  public Builder<?, ?> copyConstantFieldsToBuilder() {
    Builder<?, ?> newBuilder = createEmptyBuilder();
    newBuilder
        .setTransferRequestTrid(transferRequestTrid)
        .setTransferRequestTime(transferRequestTime)
        .setGainingRegistrarId(gainingClientId)
        .setLosingRegistrarId(losingClientId);
    return newBuilder;
  }

  /** Maps serverApproveEntities set to the individual fields. */
  static void mapServerApproveEntitiesToFields(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities,
      TransferData transferData) {
    if (isNullOrEmpty(serverApproveEntities)) {
      transferData.pollMessageId1 = null;
      transferData.pollMessageId2 = null;
      transferData.pollMessageId3 = null;
      return;
    }
    ImmutableList<Long> sortedPollMessageIds = getSortedPollMessageIds(serverApproveEntities);
    if (sortedPollMessageIds.size() >= 1) {
      transferData.pollMessageId1 = sortedPollMessageIds.get(0);
    }
    if (sortedPollMessageIds.size() >= 2) {
      transferData.pollMessageId2 = sortedPollMessageIds.get(1);
    }
    if (sortedPollMessageIds.size() >= 3) {
      transferData.pollMessageId3 = sortedPollMessageIds.get(2);
    }
  }

  /**
   * Gets poll message IDs from the given serverApproveEntities and sorted the IDs in natural order.
   */
  private static ImmutableList<Long> getSortedPollMessageIds(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
    return nullToEmpty(serverApproveEntities).stream()
        .filter(vKey -> PollMessage.class.isAssignableFrom(vKey.getKind()))
        .map(vKey -> (long) vKey.getKey())
        .sorted()
        .collect(toImmutableList());
  }

  /** Builder for {@link TransferData} because it is immutable. */
  public abstract static class Builder<T extends TransferData, B extends Builder<T, B>>
      extends BaseTransferObject.Builder<T, B> {

    /** Create a {@link Builder} wrapping a new instance. */
    protected Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    protected Builder(T instance) {
      super(instance);
    }

    public B setTransferRequestTrid(Trid transferRequestTrid) {
      getInstance().transferRequestTrid = transferRequestTrid;
      return thisCastToDerived();
    }

    public B setServerApproveEntities(
        String repoId,
        Long historyId,
        ImmutableSet<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
      getInstance().repoId = repoId;
      getInstance().historyEntryId = historyId;
      mapServerApproveEntitiesToFields(serverApproveEntities, getInstance());
      return thisCastToDerived();
    }

    @Override
    public T build() {
      if (getInstance().pollMessageId1 != null) {
        checkState(getInstance().repoId != null, "Repo id undefined");
        checkState(getInstance().historyEntryId != null, "History entry undefined");
      }
      return super.build();
    }
  }

  /**
   * Marker interface for objects that are written in anticipation of a server approval, and
   * therefore need to be deleted under any other outcome.
   */
  public interface TransferServerApproveEntity {
    VKey<? extends TransferServerApproveEntity> createVKey();
  }
}
