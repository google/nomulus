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

package google.registry.model.poll;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.annotation.Embed;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import javax.persistence.Embeddable;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;
import org.joda.time.DateTime;

/** The {@link ResponseData} returned when completing a pending action on a domain. */
@XmlTransient
@Embeddable
public class PendingActionNotificationResponse extends ImmutableObject
    implements ResponseData, UnsafeSerializable {

  /** The inner name type that contains a name and the result boolean. */
  @Embed
  @Embeddable
  static class NameOrId extends ImmutableObject implements UnsafeSerializable {
    @XmlValue
    String value;

    @XmlAttribute(name = "paResult")
    boolean actionResult;
  }

  @XmlTransient
  NameOrId nameOrId;

  @XmlElement(name = "paTRID")
  Trid trid;

  @XmlElement(name = "paDate")
  DateTime processedDate;

  public String getNameAsString() {
    return nameOrId.value;
  }

  @VisibleForTesting
  public Trid getTrid() {
    return trid;
  }

  @VisibleForTesting
  public boolean getActionResult() {
    return nameOrId.actionResult;
  }

  protected static <T extends PendingActionNotificationResponse> T init(
      T response, String nameOrId, boolean actionResult, Trid trid, DateTime processedDate) {
    response.nameOrId = new NameOrId();
    response.nameOrId.value = nameOrId;
    response.nameOrId.actionResult = actionResult;
    response.trid = trid;
    response.processedDate = processedDate;
    return response;
  }

  /** An adapter to output the XML in response to resolving a pending command on a domain. */
  @Embed
  @XmlRootElement(name = "panData", namespace = "urn:ietf:params:xml:ns:domain-1.0")
  @XmlType(
      propOrder = {"name", "trid", "processedDate"},
      namespace = "urn:ietf:params:xml:ns:domain-1.0")
  public static class DomainPendingActionNotificationResponse
      extends PendingActionNotificationResponse {

    @XmlElement
    NameOrId getName() {
      return nameOrId;
    }

    public static DomainPendingActionNotificationResponse create(
        String fullyQualifiedDomainName, boolean actionResult, Trid trid, DateTime processedDate) {
      return init(
          new DomainPendingActionNotificationResponse(),
          fullyQualifiedDomainName,
          actionResult,
          trid,
          processedDate);
    }
  }

  /** An adapter to output the XML in response to resolving a pending command on a contact. */
  @Embed
  @XmlRootElement(name = "panData", namespace = "urn:ietf:params:xml:ns:contact-1.0")
  @XmlType(
      propOrder = {"id", "trid", "processedDate"},
      namespace = "urn:ietf:params:xml:ns:contact-1.0")
  public static class ContactPendingActionNotificationResponse
      extends PendingActionNotificationResponse {

    @XmlElement
    NameOrId getId() {
      return nameOrId;
    }

    public static ContactPendingActionNotificationResponse create(
        String contactId, boolean actionResult, Trid trid, DateTime processedDate) {
      return init(
          new ContactPendingActionNotificationResponse(),
          contactId,
          actionResult,
          trid,
          processedDate);
    }
  }

  /** An adapter to output the XML in response to resolving a pending command on a host. */
  @Embed
  @XmlRootElement(name = "panData", namespace = "urn:ietf:params:xml:ns:domain-1.0")
  @XmlType(
    propOrder = {"name", "trid", "processedDate"},
    namespace = "urn:ietf:params:xml:ns:domain-1.0"
  )
  public static class HostPendingActionNotificationResponse
      extends PendingActionNotificationResponse {

    @XmlElement
    NameOrId getName() {
      return nameOrId;
    }

    public static HostPendingActionNotificationResponse create(
        String fullyQualifiedHostName, boolean actionResult, Trid trid, DateTime processedDate) {
      return init(
          new HostPendingActionNotificationResponse(),
          fullyQualifiedHostName,
          actionResult,
          trid,
          processedDate);
    }
  }
}
