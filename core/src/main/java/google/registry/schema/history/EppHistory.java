// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.history;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import org.joda.time.DateTime;

/**
 * Common fields for history objects, added to all history objects in addition to the
 * entity-specific fields.
 */
@MappedSuperclass
public abstract class EppHistory extends ImmutableObject {

  // Note: we don't reference the parent entity in this abstract class since we don't know the type
  // and we might use different VKey types for different domain/contact/host types

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  long revisionId;

  /** The type of this history object. */
  @Column(nullable = false)
  HistoryEntry.Type type;

  /** The id of the registrar that sent the command. */
  @Column(nullable = false)
  String registrarId;

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  String otherRegistrarId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  byte[] xmlBytes;

  /** Transaction id that made this change, or null if the entry was not created by a flow. */
  Trid trid;

  /** Whether this change was created by a superuser. */
  boolean bySuperuser;

  /** Reason for the change. */
  String reason;

  /** Whether this change was requested by a registrar. */
  Boolean requestedByRegistrar;

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any HistoryEntry generated before this field was added. This will
   * also be empty if the HistoryEntry refers to an EPP mutation that does not affect domain
   * transaction counts (such as contact or host mutations).
   */
  @ElementCollection Set<DomainTransactionRecord> domainTransactionRecords;

  public HistoryEntry.Type getType() {
    return type;
  }

  public byte[] getXmlBytes() {
    return xmlBytes;
  }

  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public String getOtherRegistrarId() {
    return otherRegistrarId;
  }

  /** Returns the TRID, which may be null if the entry was not created by a normal flow. */
  @Nullable
  public Trid getTrid() {
    return trid;
  }

  public boolean getBySuperuser() {
    return bySuperuser;
  }

  public String getReason() {
    return reason;
  }

  public Boolean getRequestedByRegistrar() {
    return requestedByRegistrar;
  }

  public ImmutableSet<DomainTransactionRecord> getDomainTransactionRecords() {
    return nullToEmptyImmutableCopy(domainTransactionRecords);
  }

  protected abstract static class Builder<T extends EppHistory>
      extends Buildable.Builder<T> {
    public Builder() {}

    public Builder(T instance) {
      super(instance);
    }

    public Builder<T> setType(HistoryEntry.Type type) {
      getInstance().type = type;
      return this;
    }

    public Builder<T> setXmlBytes(byte[] xmlBytes) {
      getInstance().xmlBytes = xmlBytes;
      return this;
    }

    public Builder<T> setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder<T> setOtherRegistrarId(String otherRegistrarId) {
      getInstance().otherRegistrarId = otherRegistrarId;
      return this;
    }

    public Builder<T> setTrid(Trid trid) {
      getInstance().trid = trid;
      return this;
    }

    public Builder<T> setBySuperuser(boolean bySuperuser) {
      getInstance().bySuperuser = bySuperuser;
      return this;
    }

    public Builder<T> setReason(String reason) {
      getInstance().reason = reason;
      return this;
    }

    public Builder<T> setRequestedByRegistrar(Boolean requestedByRegistrar) {
      getInstance().requestedByRegistrar = requestedByRegistrar;
      return this;
    }

    public Builder<T> setDomainTransactionRecords(
        ImmutableSet<DomainTransactionRecord> domainTransactionRecords) {
      getInstance().domainTransactionRecords = domainTransactionRecords;
      return this;
    }
  }
}
