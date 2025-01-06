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

import google.registry.model.domain.fee.FeeExtensionCommandDescriptor;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** A fee extension that may be present on domain info commands. */
@XmlRootElement(name = "info")
@XmlType(propOrder = {"currency", "command", "period"})
public class FeeInfoCommandExtensionV06
    extends FeeQueryCommandExtensionItem implements CommandExtension {

  /** A three-character ISO4217 currency code. */
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
  public CurrencyUnit getCurrency() {
    return currency;
  }

  @Override
  public Optional<DateTime> getEffectiveDate() {
    return Optional.empty();
  }
}

