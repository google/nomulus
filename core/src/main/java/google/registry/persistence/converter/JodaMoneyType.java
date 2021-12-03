// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.Type;
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
 * <p>Although {@link CompositeUserType} is likely to suffer breaking change in Hibernate 6, it is
 * the only option. The suggested alternatives such as Hibernate component or Java Embeddable do not
 * work in this case. Hibernate component (our previous solution that is replaced by this class)
 * does not allow manipulation of the loaded amount objects. Java Embeddable is not applicable since
 * we do not own the Joda money classes.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * '@'Type(type = JodaMoneyType.TYPE_NAME)
 * '@'Columns(
 *   columns = {
 *     '@'Column(name = "cost_amount"),
 *     '@'Column(name = "cost_currency")
 *   }
 * )
 * Money cost;
 * }</pre>
 */
public class JodaMoneyType implements CompositeUserType {

  public static final JodaMoneyType INSTANCE = new JodaMoneyType();

  /** The name of this type registered with JPA. See the example in class doc. */
  public static final String TYPE_NAME = "JodaMoney";

  // JPA property names that can be used in JPQL queries.
  private static final ImmutableList<String> JPA_PROPERTY_NAMES =
      ImmutableList.of("amount", "currency");
  private static final ImmutableList<Type> PROPERTY_TYPES =
      ImmutableList.of(StandardBasicTypes.BIG_DECIMAL, StandardBasicTypes.STRING);
  private static final int AMOUNT_ID = JPA_PROPERTY_NAMES.indexOf("amount");
  private static final int CURRENCY_ID = JPA_PROPERTY_NAMES.indexOf("currency");

  @Override
  public String[] getPropertyNames() {
    return JPA_PROPERTY_NAMES.toArray(new String[0]);
  }

  @Override
  public Type[] getPropertyTypes() {
    return PROPERTY_TYPES.toArray(new Type[0]);
  }

  @Override
  public Object getPropertyValue(Object component, int property) throws HibernateException {
    if (property >= JPA_PROPERTY_NAMES.size()) {
      throw new HibernateException("Property index too large: " + property);
    }
    Money money = (Money) component;
    return property == AMOUNT_ID ? money.getAmount() : money.getCurrencyUnit().getCode();
  }

  @Override
  public void setPropertyValue(Object component, int property, Object value)
      throws HibernateException {
    throw new HibernateException("Money is immutable");
  }

  @Override
  public Class returnedClass() {
    return Money.class;
  }

  @Override
  public boolean equals(Object x, Object y) throws HibernateException {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(Object x) throws HibernateException {
    return Objects.hashCode(x);
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    BigDecimal amount = StandardBasicTypes.BIG_DECIMAL.nullSafeGet(rs, names[AMOUNT_ID], session);
    CurrencyUnit currencyUnit =
        CurrencyUnit.of(StandardBasicTypes.STRING.nullSafeGet(rs, names[CURRENCY_ID], session));
    if (amount != null && currencyUnit != null) {
      return Money.of(currencyUnit, amount.stripTrailingZeros());
    }
    if (amount == null && currencyUnit == null) {
      return null;
    }
    throw new HibernateException("Mismatching null state between currency and amount.");
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    BigDecimal amount = value == null ? null : ((Money) value).getAmount();
    String currencyUnit = value == null ? null : ((Money) value).getCurrencyUnit().getCode();

    if ((amount == null && currencyUnit != null) || (amount != null && currencyUnit == null)) {
      throw new HibernateException("Mismatching null state between currency and amount.");
    }
    StandardBasicTypes.BIG_DECIMAL.nullSafeSet(st, amount, index, session);
    StandardBasicTypes.STRING.nullSafeSet(st, currencyUnit, index + 1, session);
  }

  @Override
  public Object deepCopy(Object value) throws HibernateException {
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(Object value, SharedSessionContractImplementor session)
      throws HibernateException {
    return ((Money) value);
  }

  @Override
  public Object assemble(
      Serializable cached, SharedSessionContractImplementor session, Object owner)
      throws HibernateException {
    return cached;
  }

  @Override
  public Object replace(
      Object original, Object target, SharedSessionContractImplementor session, Object owner)
      throws HibernateException {
    return original;
  }
}
