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

package google.registry.model.domain.fee;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import google.registry.model.ImmutableObject;
import google.registry.xml.PeriodAdapter;
import java.math.BigDecimal;
import java.util.stream.Stream;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.joda.time.DateTime;
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

  /** Enum for the type of the fee. */
  public enum FeeType {
    CREATE("create"),
    EAP("Early Access Period, fee expires: %s", "Early Access Period"),
    RENEW("renew"),
    RESTORE("restore"),
    /**
     * A transfer fee.
     *
     * <p>These are not used by the default system, in which the only fee associated with a transfer
     * is the RENEW fee. These exist so that custom pricing logic can create a custom transfer fee
     * if desired.
     */
    TRANSFER("transfer"),
    UPDATE("update"),
    CREDIT("%s credit");

    private final String formatString;

    private final ImmutableList<String> extraAcceptableDescriptions;

    FeeType(String formatString, String... extraAcceptableDescriptions) {
      this.formatString = formatString;
      this.extraAcceptableDescriptions = ImmutableList.copyOf(extraAcceptableDescriptions);
    }

    String renderDescription(Object... args) {
      return String.format(formatString, args);
    }

    boolean matchFormatString(String description) {
      return new ImmutableList.Builder<String>()
          .add(formatString)
          .addAll(extraAcceptableDescriptions)
          .build()
          .stream()
          .anyMatch(
              expectedDescription ->
                  Ascii.toLowerCase(description).contains(Ascii.toLowerCase(expectedDescription)));
    }
  }

  @XmlAttribute String description;

  @XmlAttribute AppliedType applied;

  @XmlAttribute(name = "grace-period")
  @XmlJavaTypeAdapter(PeriodAdapter.class)
  Period gracePeriod;

  @XmlAttribute Boolean refundable;

  @XmlValue BigDecimal cost;

  @XmlTransient FeeType type;

  @XmlTransient Range<DateTime> validDateRange;

  @XmlTransient boolean isPremium;

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

  /** Returns whether the fee in question is a premium price. */
  public boolean isPremium() {
    return isPremium;
  }

  /**
   * According to the fee extension specification, a fee must always be non-negative, while a credit
   * must always be negative. Essentially, they are the same thing, just with different sign.
   * However, we need them to be separate classes for proper JAXB handling.
   *
   * @see <a href="https://tools.ietf.org/html/draft-brown-epp-fees-03#section-2.4">Registry Fee
   *     Extension for EPP - Fees and Credits</a>
   */
  public BigDecimal getCost() {
    return cost;
  }

  public FeeType getType() {
    return type;
  }

  public boolean hasValidDateRange() {
    return validDateRange != null;
  }

  public Range<DateTime> getValidDateRange() {
    checkState(hasValidDateRange());
    return validDateRange;
  }

  public boolean hasZeroCost() {
    return cost.signum() == 0;
  }

  public boolean hasDefaultAttributes() {
    return getGracePeriod().equals(Period.ZERO)
        && getApplied().equals(AppliedType.IMMEDIATE)
        && getRefundable();
  }

  /**
   * Parses the description field and returns {@link FeeType}s that match the description.
   *
   * <p>A {@link FeeType} is a match when its {@code formatString} contains the description, case
   * insensitively.
   */
  public ImmutableList<FeeType> parseDescriptionForTypes() {
    if (description == null) {
      return ImmutableList.of();
    }
    return Stream.of(FeeType.values())
        .filter(feeType -> feeType.matchFormatString(description))
        .collect(toImmutableList());
  }
}
