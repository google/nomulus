// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain.rgp;

import google.registry.model.translators.EnumToAttributeAdapter;
import google.registry.model.translators.EnumToAttributeAdapter.EppEnum;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Represents a Registry Grace Period status, as defined by
 * <a href="https://tools.ietf.org/html/rfc3915">RFC 3915</a>.
 *
 * @see <a href="https://www.icann.org/resources/pages/epp-status-codes-2014-06-16-en">EPP Status
 *     Codes</a>
 */
@XmlJavaTypeAdapter(EnumToAttributeAdapter.class)
public enum GracePeriodStatus implements EppEnum {

  /**
   * This grace period is provided after the initial registration of a domain name. If the domain
   * name is deleted by the registrar during this period, the registry provides a credit to the
   * registrar for the cost of the registration.
   */
  ADD("addPeriod"),

  /**
   * This grace period is provided after a domain name registration period expires and is extended
   * (renewed) automatically by the registry. If the domain name is deleted by the registrar during
   * this period, the registry provides a credit to the registrar for the cost of the renewal.
   */
  AUTO_RENEW("autoRenewPeriod"),

  /**
   * This status value is used to describe a domain for which a <delete> command has been received,
   * but the domain has not yet been purged because an opportunity exists to restore the domain and
   * abort the deletion process.
   */
  REDEMPTION("redemptionPeriod"),

  /**
   * This grace period is provided after a domain name registration period is explicitly extended
   * (renewed) by the registrar. If the domain name is deleted by the registrar during this period,
   * the registry provides a credit to the registrar for the cost of the renewal.
   */
  RENEW("renewPeriod"),

  /**
   * This status value is used to describe a domain that has entered the purge processing state
   * after completing the redemptionPeriod state. A domain in this status MUST also have the EPP
   * pendingDelete status.
   */
  PENDING_DELETE("pendingDelete"),

  /**
   * This status value is used to describe a domain that is in the process of being restored after
   * being in the redemptionPeriod state.
   */
  PENDING_RESTORE("pendingRestore"),

  /**
   * This grace period is provided after the allocation of a domain name that was applied for during
   * sunrise or landrush. If the domain name is deleted by the registrar during this period, the
   * registry provides a credit to the registrar for the cost of the registration. This grace period
   * is cancelled when any nameservers are set on the domain, at which point it converts to a
   * standard add grace period.
   *
   * <p>Note that this status shows up as "addPeriod" in XML, which is the same as the add grace
   * period. This is done deliberately so as not to break the standard EPP schema.
   */
  SUNRUSH_ADD("addPeriod"),

  /**
   * This grace period is provided after the successful transfer of domain name registration
   * sponsorship from one registrar to another registrar. If the domain name is deleted by the new
   * sponsoring registrar during this period, the registry provides a credit to the registrar for
   * the cost of the transfer.
   */
  TRANSFER("transferPeriod");

  @XmlAttribute(name = "s")
  private final String xmlName;

  GracePeriodStatus(String xmlName) {
    this.xmlName = xmlName;
  }

  @Override
  public String getXmlName() {
    return xmlName;
  }

  /**
   * Maps from xmlName to {@link GracePeriodStatus}.
   *
   * If no match is found for xmlName, null is returned.
   */
  @Nullable
  public static GracePeriodStatus fromXmlName(String xmlName) {
    for (GracePeriodStatus status : GracePeriodStatus.values()) {
      if (status.xmlName.equals(xmlName)) {
        return status;
      }
    }
    return null;
  }
}
