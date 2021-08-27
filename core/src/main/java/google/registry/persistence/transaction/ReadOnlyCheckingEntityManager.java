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

import static google.registry.persistence.transaction.TransactionManagerFactory.assertNotReadOnlyMode;

import java.util.List;
import java.util.Map;
import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.Query;
import javax.persistence.StoredProcedureQuery;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaDelete;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.CriteriaUpdate;
import javax.persistence.metamodel.Metamodel;

/** An {@link EntityManager} that throws exceptions on write actions if in read-only mode. */
public class ReadOnlyCheckingEntityManager implements EntityManager {

  private final EntityManager delegate;

  public ReadOnlyCheckingEntityManager(EntityManager delegate) {
    this.delegate = delegate;
  }

  @Override
  public void persist(Object entity) {
    assertNotReadOnlyMode();
    delegate.persist(entity);
  }

  @Override
  public <T> T merge(T entity) {
    assertNotReadOnlyMode();
    return delegate.merge(entity);
  }

  @Override
  public void remove(Object entity) {
    assertNotReadOnlyMode();
    delegate.remove(entity);
  }

  @Override
  public <T> T find(Class<T> entityClass, Object primaryKey) {
    return delegate.find(entityClass, primaryKey);
  }

  @Override
  public <T> T find(Class<T> entityClass, Object primaryKey, Map<String, Object> properties) {
    return delegate.find(entityClass, primaryKey, properties);
  }

  @Override
  public <T> T find(Class<T> entityClass, Object primaryKey, LockModeType lockMode) {
    return delegate.find(entityClass, primaryKey, lockMode);
  }

  @Override
  public <T> T find(
      Class<T> entityClass,
      Object primaryKey,
      LockModeType lockMode,
      Map<String, Object> properties) {
    return delegate.find(entityClass, primaryKey, lockMode, properties);
  }

  @Override
  public <T> T getReference(Class<T> entityClass, Object primaryKey) {
    return delegate.getReference(entityClass, primaryKey);
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void setFlushMode(FlushModeType flushMode) {
    delegate.setFlushMode(flushMode);
  }

  @Override
  public FlushModeType getFlushMode() {
    return delegate.getFlushMode();
  }

  @Override
  public void lock(Object entity, LockModeType lockMode) {
    assertNotReadOnlyMode();
    delegate.lock(entity, lockMode);
  }

  @Override
  public void lock(Object entity, LockModeType lockMode, Map<String, Object> properties) {
    assertNotReadOnlyMode();
    delegate.lock(entity, lockMode, properties);
  }

  @Override
  public void refresh(Object entity) {
    delegate.refresh(entity);
  }

  @Override
  public void refresh(Object entity, Map<String, Object> properties) {
    delegate.refresh(entity, properties);
  }

  @Override
  public void refresh(Object entity, LockModeType lockMode) {
    delegate.refresh(entity, lockMode);
  }

  @Override
  public void refresh(Object entity, LockModeType lockMode, Map<String, Object> properties) {
    delegate.refresh(entity, lockMode, properties);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public void detach(Object entity) {
    delegate.detach(entity);
  }

  @Override
  public boolean contains(Object entity) {
    return delegate.contains(entity);
  }

  @Override
  public LockModeType getLockMode(Object entity) {
    return delegate.getLockMode(entity);
  }

  @Override
  public void setProperty(String propertyName, Object value) {
    delegate.setProperty(propertyName, value);
  }

  @Override
  public Map<String, Object> getProperties() {
    return delegate.getProperties();
  }

  @Override
  public ReadOnlyCheckingQuery createQuery(String qlString) {
    return new ReadOnlyCheckingQuery(delegate.createQuery(qlString));
  }

  @Override
  public <T> TypedQuery<T> createQuery(CriteriaQuery<T> criteriaQuery) {
    return new ReadOnlyCheckingTypedQuery<>(delegate.createQuery(criteriaQuery));
  }

  @Override
  public Query createQuery(CriteriaUpdate updateQuery) {
    assertNotReadOnlyMode();
    return delegate.createQuery(updateQuery);
  }

  @Override
  public Query createQuery(CriteriaDelete deleteQuery) {
    assertNotReadOnlyMode();
    return delegate.createQuery(deleteQuery);
  }

  @Override
  public <T> TypedQuery<T> createQuery(String qlString, Class<T> resultClass) {
    return new ReadOnlyCheckingTypedQuery<>(delegate.createQuery(qlString, resultClass));
  }

  @Override
  public Query createNamedQuery(String name) {
    return new ReadOnlyCheckingQuery(delegate.createNamedQuery(name));
  }

  @Override
  public <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass) {
    return new ReadOnlyCheckingTypedQuery<>(delegate.createNamedQuery(name, resultClass));
  }

  @Override
  public Query createNativeQuery(String sqlString) {
    return new ReadOnlyCheckingQuery(delegate.createNativeQuery(sqlString));
  }

  @Override
  public Query createNativeQuery(String sqlString, Class resultClass) {
    return new ReadOnlyCheckingQuery(delegate.createNativeQuery(sqlString, resultClass));
  }

  @Override
  public Query createNativeQuery(String sqlString, String resultSetMapping) {
    return new ReadOnlyCheckingQuery(delegate.createNativeQuery(sqlString, resultSetMapping));
  }

  @Override
  public StoredProcedureQuery createNamedStoredProcedureQuery(String name) {
    assertNotReadOnlyMode();
    return delegate.createNamedStoredProcedureQuery(name);
  }

  @Override
  public StoredProcedureQuery createStoredProcedureQuery(String procedureName) {
    assertNotReadOnlyMode();
    return delegate.createStoredProcedureQuery(procedureName);
  }

  @Override
  public StoredProcedureQuery createStoredProcedureQuery(
      String procedureName, Class... resultClasses) {
    assertNotReadOnlyMode();
    return delegate.createStoredProcedureQuery(procedureName, resultClasses);
  }

  @Override
  public StoredProcedureQuery createStoredProcedureQuery(
      String procedureName, String... resultSetMappings) {
    assertNotReadOnlyMode();
    return delegate.createStoredProcedureQuery(procedureName, resultSetMappings);
  }

  @Override
  public void joinTransaction() {
    delegate.joinTransaction();
  }

  @Override
  public boolean isJoinedToTransaction() {
    return delegate.isJoinedToTransaction();
  }

  @Override
  public <T> T unwrap(Class<T> cls) {
    return delegate.unwrap(cls);
  }

  @Override
  public Object getDelegate() {
    return delegate.getDelegate();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public EntityTransaction getTransaction() {
    return delegate.getTransaction();
  }

  @Override
  public EntityManagerFactory getEntityManagerFactory() {
    return delegate.getEntityManagerFactory();
  }

  @Override
  public CriteriaBuilder getCriteriaBuilder() {
    return delegate.getCriteriaBuilder();
  }

  @Override
  public Metamodel getMetamodel() {
    return delegate.getMetamodel();
  }

  @Override
  public <T> EntityGraph<T> createEntityGraph(Class<T> rootType) {
    return delegate.createEntityGraph(rootType);
  }

  @Override
  public EntityGraph<?> createEntityGraph(String graphName) {
    return delegate.createEntityGraph(graphName);
  }

  @Override
  public EntityGraph<?> getEntityGraph(String graphName) {
    return delegate.getEntityGraph(graphName);
  }

  @Override
  public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> entityClass) {
    return delegate.getEntityGraphs(entityClass);
  }

  public <T> T mergeIgnoringReadOnly(T entity) {
    return delegate.merge(entity);
  }
}
