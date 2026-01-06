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

package google.registry.model.domain.feestdv1;

import static google.registry.util.CollectionUtils.forceEmptyToNull;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.DomainObjectSpec;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import jakarta.xml.bind.annotation.XmlType;
import org.joda.time.DateTime;

/** The version 1.0 response for a domain check on a single resource. */
@XmlType(propOrder = {"object", "feeClass", "command"})
public class FeeCheckResponseExtensionItemStdV1 extends FeeCheckResponseExtensionItem {

  /** The domain that was checked. */
  DomainObjectSpec object;

  /** The command that was checked. */
  FeeCheckResponseExtensionItemCommandStdV1 command;

  /**
   * This method is overridden and not annotated for JAXB because this version of the extension
   * doesn't support "period".
   */
  @Override
  public Period getPeriod() {
    return super.getPeriod();
  }

  /**
   * This method is overridden and not annotated for JAXB because this version of the extension
   * doesn't support "fee".
   */
  @Override
  public ImmutableList<Fee> getFees() {
    return super.getFees();
  }

  /** Builder for {@link FeeCheckResponseExtensionItemStdV1}. */
  public static class Builder
      extends FeeCheckResponseExtensionItem.Builder<FeeCheckResponseExtensionItemStdV1> {

    final FeeCheckResponseExtensionItemCommandStdV1.Builder commandBuilder =
        new FeeCheckResponseExtensionItemCommandStdV1.Builder();

    @Override
    public Builder setCommand(CommandName commandName, String phase, String subphase) {
      commandBuilder.setCommandName(commandName);
      commandBuilder.setPhase(phase);
      commandBuilder.setSubphase(subphase);
      return this;
    }

    @Override
    public Builder setPeriod(Period period) {
      commandBuilder.setPeriod(period);
      return this;
    }

    @Override
    public Builder setFees(ImmutableList<Fee> fees) {
      commandBuilder.setFee(forceEmptyToNull(ImmutableList.copyOf(fees)));
      return this;
    }

    @Override
    public Builder setClass(String feeClass) {
      super.setClass(feeClass);
      return this;
    }

    @Override
    public Builder setDomainNameIfSupported(String name) {
      getInstance().object = new DomainObjectSpec(name);
      return this;
    }

    @Override
    public FeeCheckResponseExtensionItemStdV1 build() {
      getInstance().command = commandBuilder.build();
      return super.build();
    }

    @Override
    public Builder setEffectiveDateIfSupported(DateTime effectiveDate) {
      commandBuilder.setEffectiveDate(effectiveDate);
      return this;
    }

    @Override
    public Builder setNotAfterDateIfSupported(DateTime notAfterDate) {
      commandBuilder.setNotAfterDate(notAfterDate);
      return this;
    }
  }
}
