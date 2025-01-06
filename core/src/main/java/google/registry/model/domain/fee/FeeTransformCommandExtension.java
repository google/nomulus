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

package google.registry.model.domain.fee;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.util.List;
import org.joda.money.CurrencyUnit;

/** Base class for general transform commands with fees (create, renew, update, transfer). */
@XmlTransient
public abstract class FeeTransformCommandExtension
    extends ImmutableObject implements CommandExtension {

  /** The currency of the fee. */
  CurrencyUnit currency;

  /**
   * The magnitude of the fee, in the specified units, with an optional description.
   *
   * <p>This is a list because a single operation can involve multiple fees.
   */
  @XmlElement(name = "fee")
  List<Fee> fees;

  public CurrencyUnit getCurrency() {
    return currency;
  }

  public ImmutableList<Fee> getFees() {
    return nullToEmptyImmutableCopy(fees);
  }

  public abstract ImmutableList<Credit> getCredits();

  public abstract FeeTransformResponseExtension.Builder createResponseBuilder();
}
