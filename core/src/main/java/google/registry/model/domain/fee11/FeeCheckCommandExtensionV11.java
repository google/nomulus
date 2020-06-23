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

package google.registry.model.domain.fee11;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeExtensionCommandDescriptor;
import google.registry.model.domain.fee11.FeeCheckCommandExtensionV11.FeeCheckCommandExtensionItemV11;
import java.util.Optional;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/**
 * Version 0.11 of the fee extension that may be present on domain check commands. Unlike other
 * versions, there is only one check item; things are nested one level less. However, the response
 * will have multiple items, one for each domain in the check command.
 */
@XmlRootElement(name = "check")
@XmlType(propOrder = {"command", "currency", "period", "feeClass"})
public class FeeCheckCommandExtensionV11 extends ImmutableObject
    implements FeeCheckCommandExtension<
        FeeCheckCommandExtensionItemV11,
        FeeCheckResponseExtensionV11> {

  /** The default validity period (if not specified) is 1 year for all operations. */
  static final Period DEFAULT_PERIOD = Period.create(1, Period.Unit.YEARS);

  /** The command to check. */
  FeeExtensionCommandDescriptor command;

  /** Three-letter currency code in which results should be returned. */
  CurrencyUnit currency;

  /** The period to check. */
  Period period;

  /** The fee class to check. */
  @XmlElement(name = "class")
  String feeClass;

  @Override
  public CurrencyUnit getCurrency() {
    // This version of the fee extension does not have any items, and although the currency is
    // specified at the top level we've modeled it as a single fake item with the currency inside,
    // so there's no top level currency to return here.
    return null;
  }

  @Override
  public ImmutableList<FeeCheckCommandExtensionItemV11> getItems() {
    return ImmutableList.of(new FeeCheckCommandExtensionItemV11());
  }

  @Override
  public FeeCheckResponseExtensionV11 createResponse(
      ImmutableList<? extends FeeCheckResponseExtensionItem> items) {
    ImmutableList.Builder<FeeCheckResponseExtensionItemV11> builder = new ImmutableList.Builder<>();
    for (FeeCheckResponseExtensionItem item : items) {
      checkState(
          item instanceof FeeCheckResponseExtensionItemV11,
          "Fee extension response item is not V11");
      builder.add((FeeCheckResponseExtensionItemV11) item);
    }
    return FeeCheckResponseExtensionV11.create(builder.build());
  }

  /** Implementation of the item interface, returning values of the single "item". */
  class FeeCheckCommandExtensionItemV11 extends FeeCheckCommandExtensionItem {

    /** The name of the command being checked. */
    @Override
    public CommandName getCommandName() {
      return command.getCommand();
    }

    /** The command name before being parsed into an enum, for use in error strings. */
    @Override
    public String getUnparsedCommandName() {
      return command.getUnparsedCommandName();
    }

    /** The phase of the command being checked. */
    @Override
    public String getPhase() {
      return command.getPhase();
    }

    /** The subphase of the command being checked. */
    @Override
    public String getSubphase() {
      return command.getSubphase();
    }

    @Override
    public Period getPeriod() {
      return Optional.ofNullable(period).orElse(DEFAULT_PERIOD);
    }

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
      return currency;
    }

    @Override
    public FeeCheckResponseExtensionItemV11.Builder createResponseBuilder() {
      return new FeeCheckResponseExtensionItemV11.Builder();
    }

    @Override
    public Optional<DateTime> getEffectiveDate() {
      return Optional.empty();
    }
  }
}
