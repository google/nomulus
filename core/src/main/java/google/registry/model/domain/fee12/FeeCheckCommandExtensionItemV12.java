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

package google.registry.model.domain.fee12;

import com.google.common.base.Ascii;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import java.util.Locale;
import java.util.Optional;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/**
 * An individual price check item in version 0.12 of the fee extension on domain check commands.
 * Items look like:
 *
 * <pre>{@code
 * <fee:command name="renew" phase="sunrise" subphase="hello">
 *   <fee:period unit="y">1</fee:period>
 *   <fee:class>premium</fee:class>
 *   <fee:date>2017-05-17T13:22:21.0Z</fee:date>
 * </fee:command>
 * }</pre>
 *
 * In a change from previous versions of the extension, items do not contain domain names; instead,
 * the names from the non-extension check element are used.
 */
@XmlType(propOrder = {"period", "feeClass", "feeDate"})
public class FeeCheckCommandExtensionItemV12 extends FeeCheckCommandExtensionItem {

  /** The default validity period (if not specified) is 1 year for all operations. */
  static final Period DEFAULT_PERIOD = Period.create(1, Period.Unit.YEARS);

  @XmlAttribute(name = "name")
  String commandName;

  @XmlAttribute
  String phase;

  @XmlAttribute
  String subphase;

  @XmlElement(name = "class")
  String feeClass;

  @XmlElement(name = "date")
  DateTime feeDate;

  /** Version .12 does not support domain name or currency in fee extension items. */
  @Override
  public boolean isDomainNameSupported() {
    return false;
  }

  @Override
  public String getDomainName() {
    throw new UnsupportedOperationException("Domain not supported");
  }

  @Override
  public CurrencyUnit getCurrency() {
    return null;  // This version of the fee extension doesn't specify currency per-item.
  }

  @Override
  public String getUnparsedCommandName() {
    return commandName;
  }

  @Override
  public CommandName getCommandName() {
    // Require the xml string to be lowercase.
    if (commandName != null && commandName.toLowerCase(Locale.ENGLISH).equals(commandName)) {
      try {
        return CommandName.valueOf(Ascii.toUpperCase(commandName));
      } catch (IllegalArgumentException e) {
        // Swallow this and return UNKNOWN below because there's no matching CommandName.
      }
    }
    return CommandName.UNKNOWN;
  }

  @Override
  public String getPhase() {
    return phase;
  }

  @Override
  public String getSubphase() {
    return subphase;
  }

  @Override
  public FeeCheckResponseExtensionItemV12.Builder createResponseBuilder() {
    return new FeeCheckResponseExtensionItemV12.Builder();
  }

  @Override
  public Optional<DateTime> getEffectiveDate() {
    return Optional.ofNullable(feeDate);
  }
}
