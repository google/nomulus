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

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.List;
import org.joda.time.DateTime;

/** The version 1.0 response command entity for a domain check on a single resource. */
@XmlType(propOrder = {"period", "fee", "feeClass"})
public class FeeCheckResponseExtensionItemCommandStdV1 extends ImmutableObject {

  /** The command that was checked. */
  @XmlAttribute(name = "name")
  String commandName;

  /** The phase that was checked. */
  @XmlAttribute String phase;

  /** The subphase that was checked. */
  @XmlAttribute String subphase;

  /** The period that was checked. */
  Period period;

  /**
   * The magnitude of the fee, in the specified units, with an optional description.
   *
   * <p>This is a list because a single operation can involve multiple fees.
   */
  List<Fee> fee;

  /**
   * The type of the fee.
   *
   * <p>We will use "premium" for fees on premium names, and omit the field otherwise.
   */
  @XmlElement(name = "class")
  String feeClass;

  public String getFeeClass() {
    return feeClass;
  }

  /** Builder for {@link FeeCheckResponseExtensionItemCommandStdV1}. */
  public static class Builder extends Buildable.Builder<FeeCheckResponseExtensionItemCommandStdV1> {

    public Builder setCommandName(CommandName commandName) {
      getInstance().commandName = Ascii.toLowerCase(commandName.name());
      return this;
    }

    public Builder setPhase(String phase) {
      getInstance().phase = phase;
      return this;
    }

    public Builder setSubphase(String subphase) {
      getInstance().subphase = subphase;
      return this;
    }

    public Builder setPeriod(Period period) {
      getInstance().period = period;
      return this;
    }

    public Builder setFee(List<Fee> fees) {
      getInstance().fee = forceEmptyToNull(ImmutableList.copyOf(fees));
      return this;
    }

    public Builder setClass(String feeClass) {
      getInstance().feeClass = feeClass;
      return this;
    }
  }
}
