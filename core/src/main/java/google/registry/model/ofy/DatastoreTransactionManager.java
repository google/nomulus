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

import com.google.common.annotations.VisibleForTesting;
import google.registry.model.transaction.TransactionManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.joda.time.DateTime;

/** Datastore implementation of {@link TransactionManager}. */
@Singleton
public class DatastoreTransactionManager implements TransactionManager {

  private Ofy ofy;

  /** Constructs an instance. */
  @Inject
  public DatastoreTransactionManager() {
    ofy = ofy();
  }

  @VisibleForTesting
  public DatastoreTransactionManager(Ofy injectedOfy) {
    ofy = injectedOfy;
  }

  @Override
  public boolean inTransaction() {
    return ofy.inTransaction();
  }

  @Override
  public void assertInTransaction() {
    ofy.assertInTransaction();
  }

  @Override
  public <T> T transact(Work<T> work) {
    return ofy.transact(work);
  }

  @Override
  public void transact(Runnable work) {
    ofy.transact(work);
  }

  @Override
  public <T> T transactNew(Work<T> work) {
    return ofy.transactNew(work);
  }

  @Override
  public void transactNew(Runnable work) {
    ofy.transactNew(work);
  }

  @Override
  public <R> R transactNewReadOnly(Work<R> work) {
    return ofy.transactNewReadOnly(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    ofy.transactNewReadOnly(work);
  }

  @Override
  public <R> R doTransactionless(Work<R> work) {
    return ofy.doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return ofy.getTransactionTime();
  }
}
