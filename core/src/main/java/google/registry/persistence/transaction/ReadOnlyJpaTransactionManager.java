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

import google.registry.persistence.VKey;
import java.util.function.Supplier;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;

/** An implementation of {@link JpaTransactionManager} that throws exceptions on write actions. */
public class ReadOnlyJpaTransactionManager extends ReadOnlyTransactionManager
    implements JpaTransactionManager {

  private final JpaTransactionManager delegate;

  public ReadOnlyJpaTransactionManager(JpaTransactionManager delegate) {
    super(delegate);
    this.delegate = delegate;
  }

  @Override
  public EntityManager getEntityManager() {
    return new ReadOnlyEntityManager(delegate.getEntityManager());
  }

  @Override
  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return new ReadOnlyTypedQuery<>(delegate.query(sqlString, resultClass));
  }

  @Override
  public <T> TypedQuery<T> query(CriteriaQuery<T> criteriaQuery) {
    return new ReadOnlyTypedQuery<>(delegate.query(criteriaQuery));
  }

  @Override
  public Query query(String sqlString) {
    return new ReadOnlyQuery(delegate.query(sqlString));
  }

  @Override
  public <T> T transactWithoutBackup(Supplier<T> work) {
    return delegate.transactWithoutBackup(work);
  }

  @Override
  public <T> T transactNoRetry(Supplier<T> work) {
    return delegate.transactNoRetry(work);
  }

  @Override
  public void transactNoRetry(Runnable work) {
    delegate.transactNoRetry(work);
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    throw readOnlyException();
  }

  @Override
  public void teardown() {
    delegate.teardown();
  }
}
