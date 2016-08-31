// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static com.google.common.base.MoreObjects.firstNonNull;

import google.registry.model.ImmutableObject;
import google.registry.xml.PeriodAdapter;
import java.math.BigDecimal;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.joda.time.Period;

/** Base class for the fee and credit types. */
@XmlTransient
public abstract class BaseFee extends ImmutableObject {

  /** Enum for when a fee is applied. */
  public enum AppliedType {
    @XmlEnumValue("immediate")
    IMMEDIATE,

    @XmlEnumValue("delayed")
    DELAYED
  }

  @XmlAttribute
  String description;

  @XmlAttribute
  AppliedType applied;

  @XmlAttribute(name = "grace-period")
  @XmlJavaTypeAdapter(PeriodAdapter.class)
  Period gracePeriod;

  @XmlAttribute
  Boolean refundable;

  @XmlValue
  BigDecimal cost;

  public String getDescription() {
    return description;
  }

  public AppliedType getApplied() {
    return firstNonNull(applied, AppliedType.IMMEDIATE);
  }

  public Period getGracePeriod() {
    return firstNonNull(gracePeriod, Period.ZERO);
  }

  public Boolean getRefundable() {
    return firstNonNull(refundable, true);
  }

  /**
   * According to the fee extension specification, a fee must always be non-negative, while a credit
   * must always be negative. Essentially, they are the same thing, just with different sign.
   * However, we need them to be separate classes for proper JAXB handling.
   * 
   * @see "https://tools.ietf.org/html/draft-brown-epp-fees-03#section-2.4"
   */
  public BigDecimal getCost() {
    return cost;
  }

  public boolean hasDefaultAttributes() {
    return getGracePeriod().equals(Period.ZERO)
        && getApplied().equals(AppliedType.IMMEDIATE)
        && getRefundable();
  }
}
