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

package google.registry.model.history;

import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import org.joda.time.DateTime;

@MappedSuperclass
public abstract class EppHistory extends ImmutableObject implements Buildable {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  Long revisionId;

  /** The repo ID of the corresponding EPP object. */
  @Column(nullable = false)
  String repoId;

  /** The type of this history object. */
  @Column(nullable = false)
  HistoryEntry.Type type;

  /** The id of the registrar that sent the command. */
  @Column(nullable = false)
  String registrarId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** The actual EPP xml of the command, stored as bytes to be agnostic of encoding. */
  @Column(nullable = false)
  byte[] xmlBytes;

  /** Transaction id that made this change, or null if the entry was not created by a flow. */
  Trid trid;

  /** Whether this change was created by a superuser. */
  @Column(nullable = false)
  boolean bySuperuser;

  /** Reason for the change. */
  @Column(nullable = false)
  String reason;

  /** Whether this change was requested by a registrar. */
  @Column(nullable = false)
  Boolean requestedByRegistrar;

  public String getRepoId() {
    return repoId;
  }

  public HistoryEntry.Type getType() {
    return type;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  @Nullable
  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public byte[] getXmlBytes() {
    return xmlBytes;
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

  @Override
  public abstract Builder<?, ?> asBuilder();

  public abstract static class Builder<T extends EppHistory, B extends EppHistory.Builder<?, ?>>
      extends GenericBuilder<T, B> {

    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public B setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return thisCastToDerived();
    }

    public B setType(HistoryEntry.Type type) {
      getInstance().type = type;
      return thisCastToDerived();
    }

    public B setXmlBytes(byte[] xmlBytes) {
      getInstance().xmlBytes = xmlBytes;
      return thisCastToDerived();
    }

    public B setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return thisCastToDerived();
    }

    public B setTrid(Trid trid) {
      getInstance().trid = trid;
      return thisCastToDerived();
    }

    public B setBySuperuser(boolean bySuperuser) {
      getInstance().bySuperuser = bySuperuser;
      return thisCastToDerived();
    }

    public B setReason(String reason) {
      getInstance().reason = reason;
      return thisCastToDerived();
    }

    public B setRequestedByRegistrar(Boolean requestedByRegistrar) {
      getInstance().requestedByRegistrar = requestedByRegistrar;
      return thisCastToDerived();
    }
  }
}
