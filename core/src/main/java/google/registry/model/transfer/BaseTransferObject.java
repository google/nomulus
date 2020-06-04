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

import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import org.joda.time.DateTime;

/** Fields common to {@link TransferData} and {@link TransferResponse}. */
@XmlTransient
@MappedSuperclass
public abstract class BaseTransferObject extends ImmutableObject {
  /**
   * The status of the current or last transfer. Can be null if never transferred. Note that we
   * leave IgnoreSave off this field so that we can ensure that TransferData loaded from Objectify
   * will always be non-null.
   */
  @XmlElement(name = "trStatus")
  @Enumerated(EnumType.STRING)
  TransferStatus transferStatus;

  /** The gaining registrar of the current or last transfer. Can be null if never transferred. */
  @XmlElement(name = "reID")
  String gainingClientId;

  /** The time that the last transfer was requested. Can be null if never transferred. */
  @XmlElement(name = "reDate")
  DateTime transferRequestTime;

  /** The losing registrar of the current or last transfer. Can be null if never transferred. */
  @XmlElement(name = "acID")
  String losingClientId;

  /**
   * If the current transfer status is pending, then this holds the time that the transfer must be
   * acted upon before the server will automatically approve the transfer. For all other states,
   * this holds the time that the last pending transfer ended. Can be null if never transferred.
   */
  @XmlElement(name = "acDate")
  DateTime pendingTransferExpirationTime;

  public TransferStatus getTransferStatus() {
    return transferStatus;
  }

  public String getGainingClientId() {
    return gainingClientId;
  }

  public DateTime getTransferRequestTime() {
    return transferRequestTime;
  }

  public String getLosingClientId() {
    return losingClientId;
  }

  public DateTime getPendingTransferExpirationTime() {
    return pendingTransferExpirationTime;
  }

  /** Base class for builders of {@link BaseTransferObject} subclasses. */
  public abstract static class Builder<T extends BaseTransferObject, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {

    /** Create a {@link Builder} wrapping a new instance. */
    protected Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    protected Builder(T instance) {
      super(instance);
    }

    /** Set this resource's transfer status. */
    public B setTransferStatus(TransferStatus transferStatus) {
      getInstance().transferStatus = transferStatus;
      return thisCastToDerived();
    }

    /** Set the gaining registrar for a pending transfer on this resource. */
    public B setGainingClientId(String gainingClientId) {
      getInstance().gainingClientId = gainingClientId;
      return thisCastToDerived();
    }

    /** Set the time that the current transfer request was made on this resource. */
    public B setTransferRequestTime(DateTime transferRequestTime) {
      getInstance().transferRequestTime = transferRequestTime;
      return thisCastToDerived();
    }

    /** Set the losing registrar for a pending transfer on this resource. */
    public B setLosingClientId(String losingClientId) {
      getInstance().losingClientId = losingClientId;
      return thisCastToDerived();
    }

    /** Set the expiration time of the current pending transfer. */
    public B setPendingTransferExpirationTime(DateTime pendingTransferExpirationTime) {
      getInstance().pendingTransferExpirationTime = pendingTransferExpirationTime;
      return thisCastToDerived();
    }
  }
}
