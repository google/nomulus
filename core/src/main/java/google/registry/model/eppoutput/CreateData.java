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

package google.registry.model.eppoutput;

import google.registry.model.eppoutput.EppResponse.ResponseData;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import org.joda.time.DateTime;

/** The {@link ResponseData} returned when creating a resource. */
@XmlTransient
public abstract class CreateData implements ResponseData {

  @XmlElement(name = "crDate")
  protected DateTime creationDate;

  /** An acknowledgment message indicating that a contact was created. */
  @XmlRootElement(name = "creData", namespace = "urn:ietf:params:xml:ns:contact-1.0")
  @XmlType(propOrder = {"id", "creationDate"}, namespace = "urn:ietf:params:xml:ns:contact-1.0")
  public static class ContactCreateData extends CreateData {

    String id;

    public static ContactCreateData create(String id, DateTime creationDate) {
      ContactCreateData instance = new ContactCreateData();
      instance.id = id;
      instance.creationDate = creationDate;
      return instance;
    }
  }

  /** An acknowledgment message indicating that a domain was created. */
  @XmlRootElement(name = "creData", namespace = "urn:ietf:params:xml:ns:domain-1.0")
  @XmlType(
      propOrder = {"name", "creationDate", "expirationDate"},
      namespace = "urn:ietf:params:xml:ns:domain-1.0")
  public static class DomainCreateData extends CreateData {

    String name;

    @XmlElement(name = "exDate")
    DateTime expirationDate;

    public static DomainCreateData create(
        String name, DateTime creationDate, DateTime expirationDate) {
      DomainCreateData instance = new DomainCreateData();
      instance.name = name;
      instance.creationDate = creationDate;
      instance.expirationDate = expirationDate;
      return instance;
    }

    public String name() {
      return name;
    }

    public DateTime creationDate() {
      return creationDate;
    }

    public DateTime expirationDate() {
      return expirationDate;
    }
  }

  /** An acknowledgment message indicating that a host was created. */
  @XmlRootElement(name = "creData", namespace = "urn:ietf:params:xml:ns:host-1.0")
  @XmlType(propOrder = {"name", "creationDate"}, namespace = "urn:ietf:params:xml:ns:host-1.0")
  public static class HostCreateData extends CreateData {

    String name;

    public static HostCreateData create(String name, DateTime creationDate) {
      HostCreateData instance = new HostCreateData();
      instance.name = name;
      instance.creationDate = creationDate;
      return instance;
    }
  }
}
