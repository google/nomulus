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

package google.registry.beam.common;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.flogger.FluentLogger;
import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

/**
 * A database snapshot shareable by concurrent queries from multiple database clients. A snapshot is
 * uniquely identified by its {@link #getSnapshotId snapshotId}, and must stay open until all
 * concurrent queries to this snapshot have attached to it by calling {@link
 * google.registry.persistence.transaction.JpaTransactionManager#setDatabaseSnapshot}. However, it
 * can be closed before those queries complete.
 *
 * <p>This feature is <em>Postgresql-only</em>.
 *
 * <p>To support large queries, transaction isolation level is fixed at the REPEATABLE_READ to avoid
 * exhausting predicate locks at the SERIALIZABLE level.
 */
// TODO(b/193662898): vendor-independent support for richer transaction semantics.
public class DatabaseSnapshot implements AutoCloseable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private String snapshotId;
  private EntityManager entityManager;
  private EntityTransaction transaction;

  private DatabaseSnapshot() {}

  public String getSnapshotId() {
    checkState(entityManager != null, "Snapshot not opened yet.");
    checkState(entityManager.isOpen(), "Snapshot already closed.");
    return snapshotId;
  }

  private DatabaseSnapshot open() {
    entityManager = jpaTm().getStandaloneEntityManager();
    transaction = entityManager.getTransaction();
    transaction.setRollbackOnly();
    transaction.begin();

    entityManager
        .createNativeQuery("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        .executeUpdate();

    List<?> snapshotIds =
        entityManager.createNativeQuery("SELECT pg_export_snapshot();").getResultList();
    checkState(snapshotIds.size() == 1, "Unexpected number of snapshots: %s", snapshotIds.size());
    snapshotId = (String) snapshotIds.get(0);
    return this;
  }

  @Override
  public void close() {
    if (transaction != null && transaction.isActive()) {
      try {
        transaction.rollback();
      } catch (Exception e) {
        logger.atWarning().withCause(e).log("Failed to close a Database Snapshot");
      }
    }
    if (entityManager != null && entityManager.isOpen()) {
      entityManager.close();
    }
  }

  public static DatabaseSnapshot createSnapshot() {
    return new DatabaseSnapshot().open();
  }
}
