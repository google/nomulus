// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.AppEngineRule.makeRegistrar1;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import google.registry.model.registry.RegistryLockDao;
import google.registry.schema.domain.RegistryLock;
import java.sql.SQLException;
import java.util.Optional;
import javax.persistence.PersistenceException;
import org.junit.function.ThrowingRunnable;

/** Static utils for setting up and retrieving test resources from the SQL database. */
public class SqlHelper {

  public static RegistryLock saveRegistryLock(RegistryLock lock) {
    return jpaTm().transact(() -> RegistryLockDao.save(lock));
  }

  public static Optional<RegistryLock> getRegistryLockByVerificationCode(String verificationCode) {
    return jpaTm().transact(() -> RegistryLockDao.getByVerificationCode(verificationCode));
  }

  public static Optional<RegistryLock> getMostRecentRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentByRepoId(repoId));
  }

  public static Optional<RegistryLock> getMostRecentVerifiedRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentVerifiedLockByRepoId(repoId));
  }

  public static Optional<RegistryLock> getMostRecentUnlockedRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentVerifiedUnlockByRepoId(repoId));
  }

  public static ImmutableList<RegistryLock> getRegistryLocksByRegistrarId(String registrarId) {
    return jpaTm().transact(() -> RegistryLockDao.getLocksByRegistrarId(registrarId));
  }

  public static Optional<RegistryLock> getRegistryLockByRevisionId(long revisionId) {
    return jpaTm().transact(() -> RegistryLockDao.getByRevisionId(revisionId));
  }

  public static void saveRegistrar(String clientId) {
    jpaTm()
        .transact(
            () -> jpaTm().saveNew(makeRegistrar1().asBuilder().setClientId(clientId).build()));
  }

  public static void assertThrowForeignKeyViolation(ThrowingRunnable runnable) {
    PersistenceException thrown = assertThrows(PersistenceException.class, runnable);
    assertThat(Throwables.getRootCause(thrown)).isInstanceOf(SQLException.class);
    assertThat(Throwables.getRootCause(thrown))
        .hasMessageThat()
        .contains("violates foreign key constraint");
  }

  private SqlHelper() {}
}
