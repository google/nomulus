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

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.Parameter;
import javax.persistence.TemporalType;
import javax.persistence.TypedQuery;

/** A {@link TypedQuery <T>} that throws exceptions on write actions if in read-only mode. */
class ReadOnlyCheckingTypedQuery<T> implements TypedQuery<T> {

  private final TypedQuery<T> delegate;

  ReadOnlyCheckingTypedQuery(TypedQuery<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<T> getResultList() {
    return delegate.getResultList();
  }

  @Override
  public T getSingleResult() {
    return delegate.getSingleResult();
  }

  @Override
  public int executeUpdate() {
    assertNotReadOnlyMode();
    return delegate.executeUpdate();
  }

  @Override
  public TypedQuery<T> setMaxResults(int maxResult) {
    return delegate.setMaxResults(maxResult);
  }

  @Override
  public int getMaxResults() {
    return delegate.getMaxResults();
  }

  @Override
  public TypedQuery<T> setFirstResult(int startPosition) {
    return delegate.setFirstResult(startPosition);
  }

  @Override
  public int getFirstResult() {
    return delegate.getFirstResult();
  }

  @Override
  public TypedQuery<T> setHint(String hintName, Object value) {
    return delegate.setHint(hintName, value);
  }

  @Override
  public Map<String, Object> getHints() {
    return delegate.getHints();
  }

  @Override
  public <T1> TypedQuery<T> setParameter(Parameter<T1> param, T1 value) {
    return delegate.setParameter(param, value);
  }

  @Override
  public TypedQuery<T> setParameter(
      Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
    return delegate.setParameter(param, value, temporalType);
  }

  @Override
  public TypedQuery<T> setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
    return delegate.setParameter(param, value, temporalType);
  }

  @Override
  public TypedQuery<T> setParameter(String name, Object value) {
    return delegate.setParameter(name, value);
  }

  @Override
  public TypedQuery<T> setParameter(String name, Calendar value, TemporalType temporalType) {
    return delegate.setParameter(name, value, temporalType);
  }

  @Override
  public TypedQuery<T> setParameter(String name, Date value, TemporalType temporalType) {
    return delegate.setParameter(name, value, temporalType);
  }

  @Override
  public TypedQuery<T> setParameter(int position, Object value) {
    return delegate.setParameter(position, value);
  }

  @Override
  public TypedQuery<T> setParameter(int position, Calendar value, TemporalType temporalType) {
    return delegate.setParameter(position, value, temporalType);
  }

  @Override
  public TypedQuery<T> setParameter(int position, Date value, TemporalType temporalType) {
    return delegate.setParameter(position, value, temporalType);
  }

  @Override
  public Set<Parameter<?>> getParameters() {
    return delegate.getParameters();
  }

  @Override
  public Parameter<?> getParameter(String name) {
    return delegate.getParameter(name);
  }

  @Override
  public <X> Parameter<X> getParameter(String name, Class<X> type) {
    return delegate.getParameter(name, type);
  }

  @Override
  public Parameter<?> getParameter(int position) {
    return delegate.getParameter(position);
  }

  @Override
  public <X> Parameter<X> getParameter(int position, Class<X> type) {
    return delegate.getParameter(position, type);
  }

  @Override
  public boolean isBound(Parameter<?> param) {
    return delegate.isBound(param);
  }

  @Override
  public <X> X getParameterValue(Parameter<X> param) {
    return delegate.getParameterValue(param);
  }

  @Override
  public Object getParameterValue(String name) {
    return delegate.getParameterValue(name);
  }

  @Override
  public Object getParameterValue(int position) {
    return delegate.getParameterValue(position);
  }

  @Override
  public TypedQuery<T> setFlushMode(FlushModeType flushMode) {
    return delegate.setFlushMode(flushMode);
  }

  @Override
  public FlushModeType getFlushMode() {
    return delegate.getFlushMode();
  }

  @Override
  public TypedQuery<T> setLockMode(LockModeType lockMode) {
    return delegate.setLockMode(lockMode);
  }

  @Override
  public LockModeType getLockMode() {
    return delegate.getLockMode();
  }

  @Override
  public <X> X unwrap(Class<X> cls) {
    return delegate.unwrap(cls);
  }
}
