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

package google.registry.model.ofy;

import static google.registry.model.ofy.ObjectifyService.ofy;

import google.registry.persistence.transaction.TransactionManager;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/** Datastore implementation of {@link TransactionManager}. */
public class DatastoreTransactionManager implements TransactionManager {

  private Ofy injectedOfy;

  /** Constructs an instance. */
  public DatastoreTransactionManager(Ofy injectedOfy) {
    this.injectedOfy = injectedOfy;
  }

  private Ofy getOfy() {
    return injectedOfy == null ? ofy() : injectedOfy;
  }

  @Override
  public boolean inTransaction() {
    return getOfy().inTransaction();
  }

  @Override
  public void assertInTransaction() {
    getOfy().assertInTransaction();
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    return getOfy().transact(work);
  }

  @Override
  public void transact(Runnable work) {
    getOfy().transact(work);
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return getOfy().transactNew(work);
  }

  @Override
  public void transactNew(Runnable work) {
    getOfy().transactNew(work);
  }

  @Override
  public <R> R transactNewReadOnly(Supplier<R> work) {
    return getOfy().transactNewReadOnly(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    getOfy().transactNewReadOnly(work);
  }

  @Override
  public <R> R doTransactionless(Supplier<R> work) {
    return getOfy().doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return getOfy().getTransactionTime();
  }
}
