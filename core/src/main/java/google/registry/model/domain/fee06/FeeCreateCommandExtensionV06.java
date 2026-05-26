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

package google.registry.model.domain.fee06;

import com.google.common.collect.ImmutableList;
import google.registry.model.Buildable;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;

/**
 * An XML data object that represents a fee extension that may be present on EPP domain create
 * commands.
 */
@XmlRootElement(name = "create")
@XmlType(propOrder = {"currency", "fees"})
public class FeeCreateCommandExtensionV06 extends FeeCreateCommandExtension {

  @Override
  public FeeTransformResponseExtension.Builder createResponseBuilder() {
    return new FeeTransformResponseExtension.Builder(new FeeCreateResponseExtensionV06());
  }

  /** This version of the extension doesn't support the "credit" field. */
  @Override
  @XmlTransient
  public ImmutableList<Credit> getCredits() {
    return ImmutableList.of();
  }

  /** Builder for {@link FeeCreateCommandExtensionV06}. */
  public static class Builder extends Buildable.Builder<FeeCreateCommandExtensionV06> {
    public Builder setCurrency(CurrencyUnit currency) {
      getInstance().currency = currency;
      return this;
    }

    public Builder setFees(ImmutableList<Fee> fees) {
      getInstance().fees = fees;
      return this;
    }
  }
}
