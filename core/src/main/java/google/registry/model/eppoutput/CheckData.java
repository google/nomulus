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

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;
import javax.annotation.Nullable;

/** The "chkDataType" complex type. */
@XmlTransient
public abstract class CheckData extends ImmutableObject implements ResponseData {

  /** The check responses. We must explicitly list the namespaced versions of {@link Check}. */
  @XmlElements({
      @XmlElement(
          name = "cd", namespace = "urn:ietf:params:xml:ns:contact-1.0", type = ContactCheck.class),
      @XmlElement(
          name = "cd", namespace = "urn:ietf:params:xml:ns:domain-1.0", type = DomainCheck.class),
      @XmlElement(
          name = "cd", namespace = "urn:ietf:params:xml:ns:host-1.0", type = HostCheck.class)})
  ImmutableList<? extends Check> checks;

  protected static <T extends CheckData> T init(T instance, ImmutableList<? extends Check> checks) {
    instance.checks = checks;
    return instance;
  }

  public ImmutableList<? extends Check> getChecks() {
    return checks;
  }

  /** The response for a check on a single resource. */
  @XmlTransient
  public static class Check extends ImmutableObject {
    /** An element containing the name or id and availability of a resource. */
    @XmlElements({
        @XmlElement(name = "name", type = CheckName.class),
        @XmlElement(name = "id", type = CheckID.class)})
    CheckNameOrID nameOrId;

    /** A message explaining the availability of this resource. */
    String reason;

    protected static <T extends Check> T init(T instance, CheckNameOrID nameOrId, String reason) {
      instance.nameOrId = nameOrId;
      instance.reason = reason;
      return instance;
    }
  }

  /**
   * The "checkNameType" and "checkIDType" types.
   *
   * <p>Although these are specified in the Epp extension RFCs and not in RFC 5730, which implies
   * that they should be implemented per-extension, all of RFCs 5731, 5732 and 5733 define them
   * identically except for the namespace and some slightly renamed fields, allowing us to share
   * some code between the different extensions.
   */
  public abstract static class CheckNameOrID extends ImmutableObject {
    /** Whether the resource is available. */
    @XmlAttribute
    boolean avail;

    /** The name of the resource being checked. */
    @XmlValue
    String value;

    public boolean getAvail() {
      return avail;
    }

    public String getValue() {
      return value;
    }

    protected static <T extends CheckNameOrID> T init(T instance, boolean avail, String value) {
      instance.avail = avail;
      instance.value = value;
      return instance;
    }
  }

  /** The name for a resource in a check response. */
  public static class CheckName extends CheckNameOrID {
    protected static CheckName create(boolean avail, String name) {
      return init(new CheckName(), avail, name);
    }
  }

  /** The id for a resource in a check response. */
  public static class CheckID extends CheckNameOrID {
    protected static CheckID create(boolean avail, String id) {
      return init(new CheckID(), avail, id);
    }
  }

  /** A version with contact namespacing. */
  @XmlType(namespace = "urn:ietf:params:xml:ns:contact-1.0")
  public static class ContactCheck extends Check {
    public static ContactCheck create(boolean avail, String id, String reason) {
      return init(new ContactCheck(), CheckID.create(avail, id), reason);
    }
  }

  /** A version with domain namespacing. */
  @XmlType(namespace = "urn:ietf:params:xml:ns:domain-1.0")
  public static class DomainCheck extends Check {
    public static DomainCheck create(boolean avail, String name, @Nullable String reason) {
      return init(new DomainCheck(), CheckName.create(avail, name), reason);
    }

    public CheckName getName() {
      return (CheckName) nameOrId;
    }

    public String getReason() {
      return reason;
    }
  }

  /** A version with host namespacing. */
  @XmlType(namespace = "urn:ietf:params:xml:ns:host-1.0")
  public static class HostCheck extends Check {
    public static HostCheck create(boolean avail, String name, String reason) {
      return init(new HostCheck(), CheckName.create(avail, name), reason);
    }
  }

  /** A version with contact namespacing. */
  @XmlRootElement(name = "chkData", namespace = "urn:ietf:params:xml:ns:contact-1.0")
  public static class ContactCheckData extends CheckData {
    public static ContactCheckData create(ImmutableList<ContactCheck> checks) {
      return init(new ContactCheckData(), checks);
    }
  }

  /** A version with domain namespacing. */
  @XmlRootElement(name = "chkData", namespace = "urn:ietf:params:xml:ns:domain-1.0")
  public static class DomainCheckData extends CheckData {
    public static DomainCheckData create(ImmutableList<DomainCheck> checks) {
      return init(new DomainCheckData(), checks);
    }
  }
  /** A version with host namespacing. */
  @XmlRootElement(name = "chkData", namespace = "urn:ietf:params:xml:ns:host-1.0")
  public static class HostCheckData extends CheckData {
    public static HostCheckData create(ImmutableList<HostCheck> checks) {
      return init(new HostCheckData(), checks);
    }
  }
}
