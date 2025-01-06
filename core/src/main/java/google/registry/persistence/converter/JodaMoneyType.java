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

package google.registry.persistence.converter;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.ValueAccess;
import org.hibernate.usertype.CompositeUserType;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * Defines JPA mapping for {@link Money Joda Money type}.
 *
 * <p>{@code Money} is mapped to two table columns, a text {@code currency} column that stores the
 * currency code, and a numeric {@code amount} column that stores the amount.
 *
 * <p>The main purpose of this class is to normalize the amount loaded from the database. To support
 * all currency types, the scale of the numeric column is set to 2. As a result, the {@link
 * BigDecimal} instances obtained from query ResultSets all have their scale at 2. However, some
 * currency types, e.g., JPY requires that the scale be zero. This class strips trailing zeros from
 * each loaded BigDecimal, then calls the appropriate factory method for Money, which will adjust
 * the scale appropriately.
 *
 * <p>Conversion of {@code Money} is automatic. See {@link
 * google.registry.persistence.NomulusPostgreSQLDialect} for more information.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @AttributeOverride(
 *     name = "amount",
 *     // Override default (numeric(38,2)) to match real schema definition (numeric(19,2)).
 *     column = @Column(name = "cost_currency", precision = 19, scale = 2))
 * @AttributeOverride(name = "currency", column = @Column(name = "cost_currency"))
 * Money cost;
 * }</pre>
 */
public class JodaMoneyType implements CompositeUserType<Money> {

  /** Maps {@link Money} fields to database columns. */
  public static final class MoneyMapper {
    BigDecimal amount;
    String currency;
  }

  // JPA property names that can be used in JPQL queries.
  private static final ImmutableList<String> JPA_PROPERTY_NAMES =
      ImmutableList.of("amount", "currency");
  private static final int AMOUNT_ID = JPA_PROPERTY_NAMES.indexOf("amount");
  private static final int CURRENCY_ID = JPA_PROPERTY_NAMES.indexOf("currency");

  @Override
  public Object getPropertyValue(Money money, int property) throws HibernateException {
    if (property >= JPA_PROPERTY_NAMES.size()) {
      throw new HibernateException("Property index too large: " + property);
    }
    return property == AMOUNT_ID ? money.getAmount() : money.getCurrencyUnit().getCode();
  }

  @Override
  public Class returnedClass() {
    return Money.class;
  }

  @Override
  public boolean equals(Money x, Money y) {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(Money x) throws HibernateException {
    return Objects.hashCode(x);
  }

  @Override
  public Money deepCopy(Money value) throws HibernateException {
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Class<?> embeddable() {
    return MoneyMapper.class;
  }

  @Override
  public Money instantiate(ValueAccess values, SessionFactoryImplementor sessionFactory) {
    final String currency = values.getValue(CURRENCY_ID, String.class);
    final BigDecimal amount = values.getValue(AMOUNT_ID, BigDecimal.class);
    if (amount == null && currency == null) {
      return null;
    } else if (amount != null && currency != null) {
      return Money.of(CurrencyUnit.of(currency), amount.stripTrailingZeros());
    } else {
      throw new HibernateException(
          String.format(
              "Mismatching null state between currency '%s' and amount '%s'", currency, amount));
    }
  }

  @Override
  public Serializable disassemble(Money value) throws HibernateException {
    return value;
  }

  @Override
  public Money assemble(Serializable cached, Object owner) throws HibernateException {
    return (Money) cached;
  }

  @Override
  public Money replace(Money original, Money target, Object owner) throws HibernateException {
    return original;
  }
}
