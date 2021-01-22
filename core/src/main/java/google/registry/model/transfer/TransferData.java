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

package google.registry.model.transfer;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.AlsoLoad;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import javax.persistence.Transient;

/**
 * Common transfer data for {@link EppResource} types. Only applies to domains and contacts; hosts
 * are implicitly transferred with their superordinate domain.
 */
@MappedSuperclass
public abstract class TransferData<
        B extends TransferData.Builder<? extends TransferData, ? extends TransferData.Builder>>
    extends BaseTransferObject implements Buildable {

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

  /**
   * The billing event and poll messages associated with a server-approved transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain. If there is a pending transfer for a domain there should be
   * a number of poll messages and billing events for both the gaining and losing registrars. If the
   * pending transfer is explicitly approved, rejected or cancelled, the referenced entities should
   * be deleted.
   */
  @Transient
  @IgnoreSave(IfNull.class)
  Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities;

  @Ignore
  @Column(name = "transfer_repo_id")
  String repoId;

  @Ignore
  @Column(name = "transfer_history_entry_id")
  Long historyEntryId;

  // The pollMessageId1 and pollMessageId2 are used to store the IDs for gaining and losing poll
  // messages in Cloud SQL, and they are added to replace the VKeys in serverApproveEntities.
  // Although we can distinguish which is which when we construct the TransferData instance from
  // the transfer request flow, when the instance is loaded from Datastore, we cannot make this
  // distinction because they are just VKeys. Also, the only way we use serverApproveEntities is to
  // just delete all the entities referenced by the VKeys, so we don't need to make the distinction.
  @Ignore
  @Column(name = "transfer_poll_message_id_1")
  Long pollMessageId1;

  @Ignore
  @Column(name = "transfer_poll_message_id_2")
  Long pollMessageId2;

  public abstract boolean isEmpty();

  @Nullable
  public Trid getTransferRequestTrid() {
    return transferRequestTrid;
  }

  public ImmutableSet<VKey<? extends TransferServerApproveEntity>> getServerApproveEntities() {
    return nullToEmptyImmutableCopy(serverApproveEntities);
  }

  @Override
  public abstract Builder asBuilder();

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
  public B copyConstantFieldsToBuilder() {
    B newBuilder = new TypeInstantiator<B>(getClass()) {}.instantiate();
    newBuilder
        .setTransferRequestTrid(this.transferRequestTrid)
        .setTransferRequestTime(this.transferRequestTime)
        .setGainingClientId(this.gainingClientId)
        .setLosingClientId(this.losingClientId);
    return newBuilder;
  }

  void onLoad(
      @AlsoLoad("serverApproveEntities")
          Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
    mapServerApproveEntitiesToFields(serverApproveEntities, this);
  }

  @PostLoad
  void postLoad() {
    mapFieldsToServerApproveEntities();
  }

  /**
   * Reconstructs serverApproveEntities set from the individual fields, e.g. repoId, historyEntryId,
   * pollMessageId1.
   */
  void mapFieldsToServerApproveEntities() {
    if (repoId == null) {
      return;
    }
    Key<? extends EppResource> eppKey;
    if (getClass().equals(DomainBase.class)) {
      eppKey = Key.create(DomainBase.class, repoId);
    } else {
      eppKey = Key.create(ContactResource.class, repoId);
    }
    Key<HistoryEntry> historyEntryKey = Key.create(eppKey, HistoryEntry.class, historyEntryId);
    ImmutableSet.Builder<VKey<? extends TransferServerApproveEntity>> entityKeysBuilder =
        new ImmutableSet.Builder<>();
    if (pollMessageId1 != null) {
      Key<PollMessage> ofyKey = Key.create(historyEntryKey, PollMessage.class, pollMessageId1);
      entityKeysBuilder.add(PollMessage.createVKey(ofyKey));
    }
    if (pollMessageId2 != null) {
      Key<PollMessage> ofyKey = Key.create(historyEntryKey, PollMessage.class, pollMessageId2);
      entityKeysBuilder.add(PollMessage.createVKey(ofyKey));
    }
    serverApproveEntities = entityKeysBuilder.build();
  }

  /** Maps serverApproveEntities set to the individual fields. */
  static void mapServerApproveEntitiesToFields(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities,
      TransferData transferData) {
    if (isNullOrEmpty(serverApproveEntities)) {
      transferData.historyEntryId = null;
      transferData.repoId = null;
      transferData.pollMessageId1 = null;
      transferData.pollMessageId2 = null;
      return;
    }
    // Each element in serverApproveEntities should have the exact same Key<HistoryEntry> as its
    // parent. So, we can use any to set historyEntryId and repoId.
    Key<?> key = serverApproveEntities.iterator().next().getOfyKey();
    transferData.historyEntryId = key.getParent().getId();
    transferData.repoId = key.getParent().getParent().getName();

    ImmutableList<Long> sortedPollMessageIds = getSortedPollMessageIds(serverApproveEntities);
    if (sortedPollMessageIds.size() >= 1) {
      transferData.pollMessageId1 = sortedPollMessageIds.get(0);
    }
    if (sortedPollMessageIds.size() >= 2) {
      transferData.pollMessageId2 = sortedPollMessageIds.get(1);
    }
  }

  /**
   * Gets poll message IDs from the given serverApproveEntities and sorted the IDs in natural order.
   */
  private static ImmutableList<Long> getSortedPollMessageIds(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
    return nullToEmpty(serverApproveEntities).stream()
        .filter(vKey -> PollMessage.class.isAssignableFrom(vKey.getKind()))
        .map(vKey -> (long) vKey.getSqlKey())
        .sorted()
        .collect(toImmutableList());
  }

  /** Builder for {@link TransferData} because it is immutable. */
  public abstract static class Builder<T extends TransferData, B extends Builder<T, B>>
      extends BaseTransferObject.Builder<T, B> {

    /** Create a {@link Builder} wrapping a new instance. */
    public Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    protected Builder(T instance) {
      super(instance);
    }

    public B setTransferRequestTrid(Trid transferRequestTrid) {
      getInstance().transferRequestTrid = transferRequestTrid;
      return thisCastToDerived();
    }

    public B setServerApproveEntities(
        ImmutableSet<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
      getInstance().serverApproveEntities = serverApproveEntities;
      return thisCastToDerived();
    }

    @Override
    public T build() {
      mapServerApproveEntitiesToFields(getInstance().serverApproveEntities, getInstance());
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
