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

package google.registry.persistence.transaction;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.base.Function;
import google.registry.persistence.transaction.CriteriaQueryBuilder.WhereOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;

/**
 * Creates queries that can be used both for objectify and JPA.
 *
 * <p>Example usage:
 *
 * <pre>
 *    tm().createQueryComposer(EntityType.class)
 *        .where("fieldName", Comparator.EQ, "value")
 *        .orderBy("fieldName")
 *        .stream()
 * </pre>
 */
public abstract class QueryComposer<T> {

  // The class whose entities we're querying.  Note that this limits us to single table queries in
  // SQL.  In datastore, there's really no other kind of query.
  protected Class<T> entityClass;

  // Field to order by, if any.  Null if we don't care about order.
  @Nullable protected String orderBy;

  protected List<WhereClause<?>> predicates = new ArrayList<WhereClause<?>>();

  protected QueryComposer(Class<T> entityClass) {
    this.entityClass = entityClass;
  }

  /**
   * Introduce a "where" clause to the query.
   *
   * <p>Causes the query to return only results where the field and value have the relationship
   * specified by the comparator. For example, "field EQ value", "field GT value" etc.
   */
  public <U extends Comparable<? super U>> QueryComposer<T> where(
      String fieldName, Comparator comparator, U value) {
    predicates.add(new WhereClause(fieldName, comparator, value));
    return this;
  }

  /**
   * Order the query results by the value of the specified field.
   *
   * <p>TODO(mmuller): add the ability to do descending sort order.
   */
  public QueryComposer<T> orderBy(String fieldName) {
    orderBy = fieldName;
    return this;
  }

  /** Returns the first result of the query or an empty optional if there is none. */
  public abstract Optional<T> first();

  /**
   * Returns the one and only result of a query.
   *
   * <p>Throws a {@link javax.persistence.NonUniqueResultException} if there is more than one
   * result, throws {@link javax.persistence.NoResultException} if no results are found.
   */
  public abstract T getSingleResult();

  /** Returns the results of the query as a stream. */
  public abstract Stream<T> stream();

  /** Returns the number of results of the query. */
  public abstract long count();

  // We have to wrap the CriteriaQueryBuilder predicate factories in our own functions because at
  // the point where we pass them to the Comparator constructor, the compiler can't determine which
  // of the overloads to use since there is no "value" object for context.

  public static <U extends Comparable<? super U>> WhereOperator<U> equal(
      CriteriaBuilder criteriaBuilder) {
    return criteriaBuilder::equal;
  }

  public static <U extends Comparable<? super U>> WhereOperator<U> lessThan(
      CriteriaBuilder criteriaBuilder) {
    return criteriaBuilder::lessThan;
  }

  public static <U extends Comparable<? super U>> WhereOperator<U> lessThanOrEqualTo(
      CriteriaBuilder criteriaBuilder) {
    return criteriaBuilder::lessThanOrEqualTo;
  }

  public static <U extends Comparable<? super U>> WhereOperator<U> greaterThanOrEqualTo(
      CriteriaBuilder criteriaBuilder) {
    return criteriaBuilder::greaterThanOrEqualTo;
  }

  public static <U extends Comparable<? super U>> WhereOperator<U> greaterThan(
      CriteriaBuilder criteriaBuilder) {
    return criteriaBuilder::greaterThan;
  }

  /**
   * Enum used to specify comparison operations, e.g. {@code where("fieldName", Comparator.NE,
   * "someval")'}.
   *
   * <p>These contain values that specify the comparison behavior for both objectify and criteria
   * queries. For objectify, we provide a string to be appended to the field name in a {@code
   * filter()} expression. For criteria queries we provide a function that knows how to obtain a
   * {@link WhereOperator} from a {@link CriteriaBuilder}.
   *
   * <p>Note that the objectify strings for comparators other than equality are preceded by a space
   * because {@code filter()} expects the fieldname to be separated from the operator by a space.
   */
  public enum Comparator {
    /**
     * Return only records whose field is equal to the value.
     *
     * <p>Note that the datastore string for this is empty, which is consistent with the way {@code
     * filter()} works (it uses an unadorned field name to check for equality).
     */
    EQ("", QueryComposer::equal),

    /** Return only records whose field is less than the value. */
    LT(" <", QueryComposer::lessThan),

    /** Return only records whose field is less than or equal to the value. */
    LTE(" <=", QueryComposer::lessThanOrEqualTo),

    /** Return only records whose field is greater than or equal to the value. */
    GTE(" >=", QueryComposer::greaterThanOrEqualTo),

    /** Return only records whose field is greater than the value. */
    GT(" >", QueryComposer::greaterThan);

    private final String datastoreString;

    @SuppressWarnings("ImmutableEnumChecker") // Functions are immutable.
    private final Function<CriteriaBuilder, WhereOperator<?>> operatorFactory;

    Comparator(
        String datastoreString, Function<CriteriaBuilder, WhereOperator<?>> operatorFactory) {
      this.datastoreString = datastoreString;
      this.operatorFactory = operatorFactory;
    }

    public String getDatastoreString() {
      return datastoreString;
    }

    public Function<CriteriaBuilder, WhereOperator<?>> getComparisonFactory() {
      return operatorFactory;
    }
  };

  protected static class WhereClause<U extends Comparable<? super U>> {
    public String fieldName;
    public Comparator comparator;
    public U value;

    WhereClause(String fieldName, Comparator comparator, U value) {
      this.fieldName = fieldName;
      this.comparator = comparator;
      this.value = value;
    }

    public void addToCriteriaQueryBuilder(CriteriaQueryBuilder queryBuilder) {
      CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
      queryBuilder.where(
          fieldName, comparator.getComparisonFactory().apply(criteriaBuilder), value);
    }
  }
}
