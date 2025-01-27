// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.metamodel.Metamodel;
import java.util.concurrent.Callable;

/** Sub-interface of {@link TransactionManager} which defines JPA related methods. */
public interface JpaTransactionManager extends TransactionManager {

  /**
   * Returns a long-lived {@link EntityManager} not bound to a particular transaction.
   *
   * <p>Caller is responsible for closing the returned instance.
   */
  EntityManager getStandaloneEntityManager();

  /** Returns the JPA {@link Metamodel}. */
  Metamodel getMetaModel();

  /**
   * Returns the {@link EntityManager} for the current request.
   *
   * <p>The returned instance is closed when the current transaction completes.
   *
   * <p>Note that in the current implementation the entity manager is obtained from a static {@code
   * ThreadLocal} object that is set up by the outermost {@link #transact} call. Nested call sites
   * have no control over which database instance to use.
   */
  EntityManager getEntityManager();

  /**
   * Creates a JPA SQL query for the given query string and result class.
   *
   * <p>This is a convenience method for the longer {@code
   * jpaTm().getEntityManager().createQuery(...)}.
   */
  <T> TypedQuery<T> query(String sqlString, Class<T> resultClass);

  /** Creates a JPA SQL query for the given criteria query. */
  <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery);

  /**
   * Creates a JPA SQL query for the given query string.
   *
   * <p>This is a convenience method for the longer {@code
   * jpaTm().getEntityManager().createQuery(...)}.
   *
   * <p>Note that while this method can legally be used for queries that return results, <u>it
   * should not be</u>, as it does not correctly detach entities as must be done for nomulus model
   * objects.
   */
  Query query(String sqlString);

  /** Deletes the entity by its id, throws exception if the entity is not deleted. */
  <T> void assertDelete(VKey<T> key);

  /**
   * Releases all resources and shuts down.
   *
   * <p>The errorprone check forbids injection of {@link java.io.Closeable} resources.
   */
  void teardown();

  /**
   * Sets the JDBC driver fetch size for the {@code query}. This overrides the default
   * configuration.
   */
  static Query setQueryFetchSize(Query query, int fetchSize) {
    return query.setHint("org.hibernate.fetchSize", fetchSize);
  }

  /** Return the default {@link TransactionIsolationLevel} specified via the config file. */
  TransactionIsolationLevel getDefaultTransactionIsolationLevel();

  /** Return the {@link TransactionIsolationLevel} used in the current transaction. */
  TransactionIsolationLevel getCurrentTransactionIsolationLevel();

  /** Executes the work with the given isolation level, possibly logging all SQL statements used. */
  <T> T transact(
      TransactionIsolationLevel isolationLevel, Callable<T> work, boolean logSqlStatements);

  /**
   * Executes the work with the given isolation level without retry, possibly logging all SQL
   * statements used.
   */
  <T> T transactNoRetry(
      TransactionIsolationLevel isolationLevel, Callable<T> work, boolean logSqlStatements);
}
