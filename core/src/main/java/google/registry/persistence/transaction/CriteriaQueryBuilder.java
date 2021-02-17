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

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

/**
 * An extension of {@link CriteriaQuery} that uses a Builder-style pattern when adding "WHERE"
 * and/or "ORDER BY" clauses.
 *
 * <p>{@link CriteriaQuery}, as is, requires that all clauses must be passed in at once -- if one
 * calls "WHERE" multiple times, the later call overwrites the earlier call.
 */
public class CriteriaQueryBuilder<T> {

  /** Functional interface that defines the 'where' operator, e.g. {@link CriteriaBuilder#equal}. */
  public interface WhereClause<U> {
    Predicate predicate(Expression<U> expression, U object);
  }

  /** Functional interface that defines the order-by operator, e.g. {@link CriteriaBuilder#asc}. */
  public interface OrderByClause<U> {
    Order order(Expression<U> expression);
  }

  private final CriteriaQuery<T> query;
  private final Root<T> root;
  private final ImmutableList.Builder<Predicate> predicates = new ImmutableList.Builder<>();
  private final ImmutableList.Builder<Order> orders = new ImmutableList.Builder<>();

  private CriteriaQueryBuilder(CriteriaQuery<T> query, Root<T> root) {
    this.query = query;
    this.root = root;
  }

  /** Adds a WHERE clause to the query, given the specified operation, field, and value. */
  public <V> CriteriaQueryBuilder<T> where(WhereClause<V> whereClause, String fieldName, V value) {
    Expression<V> expression = root.get(fieldName);
    return where(whereClause.predicate(expression, value));
  }

  /** Adds a WHERE clause to the query specifying that a value must be in the given collection. */
  public CriteriaQueryBuilder<T> whereFieldIsIn(String fieldName, Collection<?> values) {
    return where(root.get(fieldName).in(values));
  }

  /** Orders the result by the given operation applied to the given field. */
  public <U> CriteriaQueryBuilder<T> orderBy(OrderByClause<U> orderByClause, String fieldName) {
    Expression<U> expression = root.get(fieldName);
    return orderBy(orderByClause.order(expression));
  }

  /** Builds and returns the query, applying all WHERE and ORDER BY clauses at once. */
  public CriteriaQuery<T> build() {
    Predicate[] predicateArray = predicates.build().toArray(new Predicate[0]);
    return query.where(predicateArray).orderBy(orders.build());
  }

  private CriteriaQueryBuilder<T> where(Predicate predicate) {
    predicates.add(predicate);
    return this;
  }

  private CriteriaQueryBuilder<T> orderBy(Order order) {
    orders.add(order);
    return this;
  }

  /** Creates a query builder that will SELECT from the given class. */
  public static <T> CriteriaQueryBuilder<T> create(Class<T> clazz) {
    CriteriaQuery<T> query = jpaTm().getEntityManager().getCriteriaBuilder().createQuery(clazz);
    Root<T> root = query.from(clazz);
    query = query.select(root);
    return new CriteriaQueryBuilder<>(query, root);
  }
}
