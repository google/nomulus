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

import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeExtensionCommandDescriptor;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import jakarta.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;

/** The version 0.6 response for a domain check on a single resource. */
@XmlType(propOrder = {"name", "currency", "command", "period", "fees", "feeClass"})
public class FeeCheckResponseExtensionItemV06 extends FeeCheckResponseExtensionItem {
  /** The name of the domain that was checked, with an attribute indicating if it is premium. */
  String name;

  CurrencyUnit currency;

  /** The command that was checked. */
  FeeExtensionCommandDescriptor command;

  /** Builder for {@link FeeCheckResponseExtensionItemV06}. */
  public static class Builder
      extends FeeCheckResponseExtensionItem.Builder<FeeCheckResponseExtensionItemV06> {

    @Override
    public Builder setCommand(CommandName commandName, String phase, String subphase) {
      getInstance().command = FeeExtensionCommandDescriptor.create(commandName, phase, subphase);
      return this;
    }

    @Override
    public Builder setDomainNameIfSupported(String name) {
      getInstance().name = name;
      return this;
    }

    @Override
    public Builder setCurrencyIfSupported(CurrencyUnit currency) {
      getInstance().currency = currency;
      return this;
    }
  }
}
