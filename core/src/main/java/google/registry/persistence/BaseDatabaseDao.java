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

package google.registry.persistence;

import google.registry.model.transaction.DatabaseTransactionManager;
import google.registry.model.transaction.TransactionManagerModule.AppEngineTM;
import javax.inject.Inject;
import javax.persistence.EntityManager;

/**
 * Base database DAO that provides a method to get {@link EntityManager} used for the current
 * transaction.
 */
public abstract class BaseDatabaseDao {

  @Inject @AppEngineTM DatabaseTransactionManager dbtm;

  public EntityManager getEntityManager() {
    return dbtm.getEntityManager();
  }
}
