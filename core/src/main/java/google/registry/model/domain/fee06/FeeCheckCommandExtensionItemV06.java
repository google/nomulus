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

package google.registry.model.domain.fee06;

import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import google.registry.model.domain.fee.FeeExtensionCommandDescriptor;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** An individual price check item in version 0.6 of the fee extension on Check commands. */
@XmlType(propOrder = {"name", "currency", "command", "period"})
public class FeeCheckCommandExtensionItemV06 extends FeeCheckCommandExtensionItem {

  /** The fully qualified domain name being checked. */
  String name;

  CurrencyUnit currency;

  /** The command being checked.  */
  FeeExtensionCommandDescriptor command;

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
  public boolean isDomainNameSupported() {
    return true;
  }

  @Override
  public String getDomainName() {
    return name;
  }

  @Override
  public CurrencyUnit getCurrency() {
    return currency;
  }

  @Override
  public FeeCheckResponseExtensionItemV06.Builder createResponseBuilder() {
    return new FeeCheckResponseExtensionItemV06.Builder();
  }

  @Override
  public Optional<DateTime> getEffectiveDate() {
    return Optional.empty();
  }
}
