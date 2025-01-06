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

import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.util.List;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/**
 * Abstract base class for the fee request query items used in Check and Info responses. It handles
 * command, period, fees and class, which are always present. Derived classes must handle currency,
 * which may or may not be present, depending on the version of the extension being used.
 */
@XmlTransient
public class FeeQueryResponseExtensionItem extends ImmutableObject {

  /**
   * The period that was checked.
   *
   * <p>This field is exposed to JAXB only via the getter so that subclasses can override it.
   */
  @XmlTransient
  Period period;

  /**
   * The magnitude of the fee, in the specified units, with an optional description.
   *
   * <p>This is a list because a single operation can involve multiple fees.
   *
   * <p>This field is exposed to JAXB only via the getter so that subclasses can override it.
   */
  @XmlTransient
  List<Fee> fees;

  /**
   * The type of the fee.
   *
   * <p>We will use "premium" for fees on premium names, and omit the field otherwise.
   *
   * <p>This field is exposed to JAXB only via the getter so that subclasses can override it.
   */
  @XmlTransient
  String feeClass;

  @XmlElement(name = "period")
  public Period getPeriod() {
    return period;
  }

  @XmlElement(name = "fee")
  public ImmutableList<Fee> getFees() {
    return nullToEmptyImmutableCopy(fees);
  }

  @XmlElement(name = "class")
  public String getFeeClass() {
    return feeClass;
  }

  /** Abstract builder for {@link FeeQueryResponseExtensionItem}. */
  public abstract static class
      Builder<T extends FeeQueryResponseExtensionItem, B extends Builder<?, ?>>
          extends GenericBuilder<T, B> {

    public abstract B setCommand(CommandName commandName, String phase, String subphase);

    public B setPeriod(Period period) {
      getInstance().period = period;
      return thisCastToDerived();
    }

    public B setFees(ImmutableList<Fee> fees) {
      // If there are no fees, set the field to null to suppress the 'fee' section in the xml.
      getInstance().fees = forceEmptyToNull(ImmutableList.copyOf(fees));
      return thisCastToDerived();
    }

    public B setClass(String feeClass) {
      getInstance().feeClass = feeClass;
      return thisCastToDerived();
    }

    public B setAvailIfSupported(@SuppressWarnings("unused") boolean avail) {
      return thisCastToDerived();  // Default impl is a noop.
    }

    public B setReasonIfSupported(@SuppressWarnings("unused") String reason) {
      return thisCastToDerived();  // Default impl is a noop.
    }

    public B setEffectiveDateIfSupported(@SuppressWarnings("unused") DateTime effectiveDate) {
      return thisCastToDerived();  // Default impl is a noop.
    }

    public B setNotAfterDateIfSupported(@SuppressWarnings("unused") DateTime notAfterDate) {
      return thisCastToDerived();  // Default impl is a noop.
    }

    public B setCurrencyIfSupported(@SuppressWarnings("unused") CurrencyUnit currency) {
      return thisCastToDerived();  // Default impl is a noop.
    }
  }
}
