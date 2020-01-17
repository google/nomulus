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

package google.registry.model.eppcommon;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_UNDERSCORE;
import static com.google.common.base.Strings.nullToEmpty;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.translators.EnumToAttributeAdapter.EppEnum;
import google.registry.model.translators.StatusValueAdapter;
import google.registry.persistence.EnumSetUserType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Represents an EPP status value for hosts, contacts, and domains, as defined in RFC 5731, 5732,
 * and 5733. The values here are the union of all 3 sets of status values.
 *
 * <p>The RFCs define extra optional metadata (language and message) that we don't use and therefore
 * don't model.
 *
 * <p>Note that {@code StatusValue.LINKED} should never be stored. Rather, it should be calculated
 * on the fly whenever needed using an eventually consistent query (i.e. in info flows).
 *
 * @see <a href="https://www.icann.org/resources/pages/epp-status-codes-2014-06-16-en">EPP Status
 *     Codes</a>
 * @see <a href="https://tools.ietf.org/html/rfc5731#section-2.3">RFC 5731 (Domain) Section 2.3</a>
 * @see <a href="https://tools.ietf.org/html/rfc5732#section-2.3">RFC 5732 (Host) Section 2.3</a>
 * @see <a href="https://tools.ietf.org/html/rfc5733#section-2.2">RFC 5733 (Contact) Section 2.2</a>
 */
@XmlJavaTypeAdapter(StatusValueAdapter.class)
public enum StatusValue implements EppEnum {
  CLIENT_DELETE_PROHIBITED(AllowedOn.ALL),
  CLIENT_HOLD(AllowedOn.ALL),
  CLIENT_RENEW_PROHIBITED(AllowedOn.ALL),
  CLIENT_TRANSFER_PROHIBITED(AllowedOn.ALL),
  CLIENT_UPDATE_PROHIBITED(AllowedOn.ALL),

  /** A status for a domain with no nameservers. */
  INACTIVE(AllowedOn.DOMAINS),

  /**
   * A status for a resource has an incoming reference from an active domain.
   *
   * <p>LINKED is a "virtual" status value that should never be persisted to Datastore on any
   * resource. It must be computed on the fly when we need it, as the set of domains using a
   * resource can change at any time.
   */
  LINKED(AllowedOn.NONE),

  /**
   * A status for a resource that has no other statuses.
   *
   * <p>For domains, OK is only present when absolutely no other statuses are present. For contacts
   * and hosts, the spec also allows a resource to have {@link #LINKED} along with OK, but we
   * implement LINKED as a virtual status that gets appended to outputs (such as info commands) on
   * the fly, so we can ignore LINKED when dealing with persisted resources.
   */
  OK(AllowedOn.ALL),

  /**
   * A status for a resource undergoing asynchronous creation.
   *
   * <p>This status is here for completeness, but it is not used by our system.
   */
  PENDING_CREATE(AllowedOn.NONE),

  /**
   * A status for a resource indicating that deletion has been requested but has not yet happened.
   *
   * <p>Contacts and hosts are deleted asynchronously because we need to check their incoming
   * references with strong consistency, requiring a mapreduce, and during that asynchronous process
   * they have the PENDING_DELETE status.
   *
   * <p>Domains in the add grace period are deleted synchronously and do not ever have this status.
   * Otherwise, domains go through an extended deletion process, consisting of a 30-day redemption
   * grace period followed by a 5-day "pending delete" period before they are actually 100% deleted.
   * These domains have the PENDING_DELETE status throughout that 35-day window.
   */
  PENDING_DELETE(AllowedOn.ALL),

  /**
   * A status for a resource with an unresolved transfer request.
   *
   * <p>Hosts transfer indirectly via superordinate domain.
   */
  // TODO(b/34844887): Remove PENDING_TRANSFER from all host resources and forbid it here.
  PENDING_TRANSFER(AllowedOn.ALL),

  /**
   * A status for a resource undergoing an asynchronous update.
   *
   * <p>This status is here for completeness, but it is not used by our system.
   */
  PENDING_UPDATE(AllowedOn.NONE),


  /** A non-client-settable status that prevents deletes of EPP resources. */
  SERVER_DELETE_PROHIBITED(AllowedOn.ALL),

  SERVER_HOLD(AllowedOn.ALL),
  SERVER_RENEW_PROHIBITED(AllowedOn.ALL),

  /** A non-client-settable status that prevents transfers of EPP resources. */
  SERVER_TRANSFER_PROHIBITED(AllowedOn.ALL),

  /** A non-client-settable status that prevents updates of EPP resources, except by superusers. */
  SERVER_UPDATE_PROHIBITED(AllowedOn.ALL);

  private final String xmlName = UPPER_UNDERSCORE.to(LOWER_CAMEL, name());
  private final AllowedOn allowedOn;

  /** Enum to help clearly list which resource types a status value is allowed to be present on. */
  private enum AllowedOn {
    ALL(ContactResource.class, DomainBase.class, HostResource.class),
    NONE,
    DOMAINS(DomainBase.class);

    private final ImmutableSet<Class<? extends EppResource>> classes;

    @SafeVarargs
    AllowedOn(Class<? extends EppResource>... classes) {
      this.classes = ImmutableSet.copyOf(classes);
    }
  }

  StatusValue(AllowedOn allowedOn) {
    this.allowedOn = allowedOn;
  }

  @Override
  public String getXmlName() {
    return xmlName;
  }

  public boolean isClientSettable() {
    // This is the actual definition of client-settable statuses; see RFC5730 section 2.3.
    return xmlName.startsWith("client");
  }

  public boolean isChargedStatus() {
    return xmlName.startsWith("server") && xmlName.endsWith("Prohibited");
  }

  public boolean isAllowedOn(Class<? extends EppResource> resource) {
    return allowedOn.classes.contains(resource);
  }

  public static StatusValue fromXmlName(String xmlName) {
    return StatusValue.valueOf(LOWER_CAMEL.to(UPPER_UNDERSCORE, nullToEmpty(xmlName)));
  }

  /** Hibernate type for sets of {@link StatusValue}. */
  public static class StatusValueSetType extends EnumSetUserType<StatusValue> {
    @Override
    protected Object convertToElem(Object value) {
      return StatusValue.valueOf((String) value);
    }
  }
}
