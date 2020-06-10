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

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.EppResource;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import javax.persistence.Embeddable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import org.joda.time.DateTime;

/**
 * A response to a transfer command on a {@link EppResource}. This base class contains fields that
 * are common to all transfer responses; derived classes add resource specific fields.
 */
@XmlTransient
@Embeddable
public class TransferResponse extends BaseTransferObject implements ResponseData {

  /** An adapter to output the XML in response to a transfer command on a domain. */
  @Embed
  @XmlRootElement(name = "trnData", namespace = "urn:ietf:params:xml:ns:domain-1.0")
  @XmlType(propOrder = {
      "fullyQualifiedDomainName",
      "transferStatus",
      "gainingClientId",
      "transferRequestTime",
      "losingClientId",
      "pendingTransferExpirationTime",
      "extendedRegistrationExpirationTime"},
    namespace = "urn:ietf:params:xml:ns:domain-1.0")
  public static class DomainTransferResponse extends TransferResponse {

    @XmlElement(name = "name")
    String fullyQualifiedDomainName;

    public String getFullyQualifiedDomainName() {
      return fullyQualifiedDomainName;
    }

    /**
     * The new registration expiration time that will take effect if this transfer is approved. This
     * will only be set on pending or approved transfers, not on cancelled or rejected ones.
     */
    @XmlElement(name = "exDate")
    DateTime extendedRegistrationExpirationTime;

    public DateTime getExtendedRegistrationExpirationTime() {
      return extendedRegistrationExpirationTime;
    }

    /** Builder for {@link DomainTransferResponse}. */
    public static class Builder
        extends BaseTransferObject.Builder<DomainTransferResponse, Builder> {
      public Builder setFullyQualifiedDomainName(String fullyQualifiedDomainName) {
        getInstance().fullyQualifiedDomainName = fullyQualifiedDomainName;
        return this;
      }

      /** Set the registration expiration time that will take effect if this transfer completes. */
      public Builder setExtendedRegistrationExpirationTime(
          DateTime extendedRegistrationExpirationTime) {
        getInstance().extendedRegistrationExpirationTime = extendedRegistrationExpirationTime;
        return this;
      }
    }
  }

  /** An adapter to output the XML in response to a transfer command on a contact. */
  @Embed
  @XmlRootElement(name = "trnData", namespace = "urn:ietf:params:xml:ns:contact-1.0")
  @XmlType(propOrder = {
      "contactId",
      "transferStatus",
      "gainingClientId",
      "transferRequestTime",
      "losingClientId",
      "pendingTransferExpirationTime"},
    namespace = "urn:ietf:params:xml:ns:contact-1.0")
  public static class ContactTransferResponse extends TransferResponse {

    @XmlElement(name = "id")
    String contactId;

    public String getContactId() {
      return contactId;
    }

    /** Builder for {@link ContactTransferResponse}. */
    public static class Builder
        extends BaseTransferObject.Builder<ContactTransferResponse, Builder> {
      public Builder setContactId(String contactId) {
        getInstance().contactId = contactId;
        return this;
      }
    }
  }
}
