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

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.eppcommon.Trid;
import google.registry.persistence.VKey;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
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

  // The following 3 fields are the replacement for serverApproveEntities in Cloud SQL.
  // TODO(shicong): Add getter/setter for these 3 fields and use them in the application code.
  @Ignore
  @Column(name = "transfer_gaining_poll_message_id")
  Long gainingTransferPollMessageId;

  @Ignore
  @Column(name = "transfer_losing_poll_message_id")
  Long losingTransferPollMessageId;

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
        // .setTransferPeriod(this.transferPeriod)
        .setTransferRequestTrid(this.transferRequestTrid)
        .setTransferRequestTime(this.transferRequestTime)
        .setGainingClientId(this.gainingClientId)
        .setLosingClientId(this.losingClientId);
    return newBuilder;
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
