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

import google.registry.persistence.transaction.CriteriaQueryBuilder.WhereClause;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;

/**
 * Creates queries that can be used both for objectify and JPA.
 *
 * <p>Example usage:
 *
 * <p>tm().createQueryComposer(EntityType.class) .where("fieldName", Comparator.EQ, "value"
 * .orderBy("fieldName") .stream()
 */
public abstract class QueryComposer<T> {

  // The class whose entities we're querying.  Note that this limits us to single table queries in
  // SQL.  In datastore, there's really no other kind of query.
  protected Class<T> entity;

  // Field to order by, if any.  Null if we don't care about order.
  @Nullable protected String orderBy;

  protected List<WhereCondition<?>> predicates = new ArrayList<WhereCondition<?>>();

  protected QueryComposer(Class<T> entity) {
    this.entity = entity;
  }

  /**
   * Introduce a "where" clause to the query.
   *
   * <p>Causes the query to return only results where the field and value have the relationship
   * specified by the comparator. For example, "field EQ value", "field GT value" etc.
   */
  public <U extends Comparable<? super U>> QueryComposer<T> where(
      String fieldName, Comparator comparator, U value) {
    predicates.add(new WhereCondition(fieldName, comparator, value));
    return this;
  }

  /** Order the query results by the value of the specified field. */
  public QueryComposer<T> orderBy(String fieldName) {
    orderBy = fieldName;
    return this;
  }

  /**
   * Returns the first result of the query.
   *
   * <p>Throws javax.persistence.NoResultException if not found.
   */
  public abstract T first();

  /** Returns the results of the query as a stream. */
  public abstract Stream<T> stream();

  // We have to wrap the CriteriaQueryBuilder predicate factories in our own functions because at
  // the point where we pass them to the Comparator constructor, the compiler can't determine which
  // of the overloads to use since there is no "value" object for context.
  // The only place where this context exists is in the call to the "where" method, which is why we
  // wrap the entire "where" method instead of just the original predicate factory.

  @com.google.errorprone.annotations.Immutable
  private interface ConditionAppender {
    <U extends Comparable<? super U>> void add(
        CriteriaQueryBuilder queryBuilder,
        CriteriaBuilder criteriaBuilder,
        String fieldName,
        U value);
  }

  private static <U extends Comparable<? super U>> void addEqualCond(
      CriteriaQueryBuilder queryBuilder,
      CriteriaBuilder criteriaBuilder,
      String fieldName,
      U value) {
    queryBuilder.where(fieldName, criteriaBuilder::equal, value);
  }

  private static <U extends Comparable<? super U>> void addLessThanCond(
      CriteriaQueryBuilder queryBuilder,
      CriteriaBuilder criteriaBuilder,
      String fieldName,
      U value) {
    queryBuilder.where(fieldName, (WhereClause<U>) criteriaBuilder::lessThan, value);
  }

  private static <U extends Comparable<? super U>> void addLessThanOrEqualToCond(
      CriteriaQueryBuilder queryBuilder,
      CriteriaBuilder criteriaBuilder,
      String fieldName,
      U value) {
    queryBuilder.where(fieldName, (WhereClause<U>) criteriaBuilder::lessThanOrEqualTo, value);
  }

  private static <U extends Comparable<? super U>> void addGreaterThanOrEqualToCond(
      CriteriaQueryBuilder queryBuilder,
      CriteriaBuilder criteriaBuilder,
      String fieldName,
      U value) {
    queryBuilder.where(fieldName, (WhereClause<U>) criteriaBuilder::greaterThanOrEqualTo, value);
  }

  private static <U extends Comparable<? super U>> void addGreaterThanCond(
      CriteriaQueryBuilder queryBuilder,
      CriteriaBuilder criteriaBuilder,
      String fieldName,
      U value) {
    queryBuilder.where(fieldName, (WhereClause<U>) criteriaBuilder::greaterThan, value);
  }

  public enum Comparator {
    EQ("", QueryComposer::addEqualCond),
    LT(" <", QueryComposer::addLessThanCond),
    LTE(" <=", QueryComposer::addLessThanOrEqualToCond),
    GTE(" >=", QueryComposer::addGreaterThanOrEqualToCond),
    GT(" >", QueryComposer::addGreaterThanCond);

    private final String datastoreString;
    private final ConditionAppender conditionAppender;

    Comparator(String datastoreString, ConditionAppender conditionAppender) {
      this.datastoreString = datastoreString;
      this.conditionAppender = conditionAppender;
    }

    public String getDatastoreString() {
      return datastoreString;
    }
  };

  protected static class WhereCondition<U extends Comparable<? super U>> {
    public String fieldName;
    public Comparator comparator;
    public U value;

    WhereCondition(String fieldName, Comparator comparator, U value) {
      this.fieldName = fieldName;
      this.comparator = comparator;
      this.value = value;
    }

    public void addToCriteriaQueryBuilder(
        CriteriaQueryBuilder queryBuilder, CriteriaBuilder criteriaBuilder) {
      comparator.conditionAppender.add(queryBuilder, criteriaBuilder, fieldName, (U) value);
    }
  }
}
