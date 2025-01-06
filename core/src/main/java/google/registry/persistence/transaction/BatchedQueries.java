// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.collect.UnmodifiableIterator;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import java.util.Optional;
import java.util.stream.Stream;

/** Helper for querying large data sets in batches. */
public final class BatchedQueries {

  private BatchedQueries() {}

  private static final int DEFAULT_BATCH_SIZE = 500;

  public static <T> Stream<ImmutableList<T>> loadAllOf(Class<T> entityType) {
    return loadAllOf(entityType, DEFAULT_BATCH_SIZE);
  }

  public static <T> Stream<ImmutableList<T>> loadAllOf(Class<T> entityType, int batchSize) {
    return loadAllOf(tm(), entityType, batchSize);
  }

  /**
   * Loads all entities of type {@code T} in batches.
   *
   * <p>This method must not be nested in any transaction; same for the traversal of the returned
   * {@link Stream}. Each batch is loaded in a separate transaction at the {@code
   * TRANSACTION_REPEATABLE_READ} isolation level, and loads the snapshot of the batch at the
   * batch's start time. New insertions or updates since then are not reflected in the result.
   */
  public static <T> Stream<ImmutableList<T>> loadAllOf(
      JpaTransactionManager jpaTm, Class<T> entityType, int batchSize) {
    checkState(!jpaTm.inTransaction(), "loadAllOf cannot be nested in a transaction");
    checkArgument(batchSize > 0, "batchSize must be positive");
    EntityType<T> jpaEntityType = jpaTm.getMetaModel().entity(entityType);
    if (!jpaEntityType.hasSingleIdAttribute()) {
      // We should support multi-column primary keys on a case-by-case basis.
      throw new UnsupportedOperationException(
          "Types with multi-column primary key not supported yet");
    }
    return Streams.stream(
        new BatchedIterator<>(new SingleColIdBatchQuery<>(jpaTm, jpaEntityType), batchSize));
  }

  public interface BatchQuery<T> {
    ImmutableList<T> readBatch(Optional<T> lastRead, int batchSize);
  }

  private static class SingleColIdBatchQuery<T> implements BatchQuery<T> {

    private final JpaTransactionManager jpaTm;
    private final Class<T> entityType;
    private final String initialJpqlQuery;
    private final String subsequentJpqlTemplate;

    private SingleColIdBatchQuery(JpaTransactionManager jpaTm, EntityType<T> jpaEntityType) {
      checkArgument(
          jpaEntityType.hasSingleIdAttribute(),
          "%s must have a single ID attribute",
          jpaEntityType.getJavaType().getSimpleName());
      this.jpaTm = jpaTm;
      this.entityType = jpaEntityType.getJavaType();
      var idAttr = jpaEntityType.getId(jpaEntityType.getIdType().getJavaType());
      this.initialJpqlQuery =
          String.format("FROM %s ORDER BY %s", jpaEntityType.getName(), idAttr.getName());
      this.subsequentJpqlTemplate =
          String.format(
              "FROM %1$s WHERE %2$s > :id ORDER BY %2$s",
              jpaEntityType.getName(), idAttr.getName());
    }

    @Override
    public ImmutableList<T> readBatch(Optional<T> lastRead, int batchSize) {
      checkState(!jpaTm.inTransaction(), "Stream cannot be accessed in a transaction");
      return jpaTm.transact(
          TRANSACTION_REPEATABLE_READ,
          () -> {
            var entityManager = jpaTm.getEntityManager();
            Optional<Object> lastReadId =
                lastRead.map(
                    entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                        ::getIdentifier);
            TypedQuery<T> query =
                lastRead.isEmpty()
                    ? entityManager.createQuery(initialJpqlQuery, entityType)
                    : entityManager
                        .createQuery(subsequentJpqlTemplate, entityType)
                        .setParameter("id", lastReadId.get());

            var results = ImmutableList.copyOf(query.setMaxResults(batchSize).getResultList());
            results.forEach(entityManager::detach);
            return results;
          });
    }
  }

  private static class BatchedIterator<T> extends UnmodifiableIterator<ImmutableList<T>> {

    private final BatchQuery<T> batchQuery;

    private final int batchSize;

    private ImmutableList<T> cachedBatch = null;

    private BatchedIterator(BatchQuery<T> batchQuery, int batchSize) {
      this.batchQuery = batchQuery;
      this.batchSize = batchSize;
      this.cachedBatch = readNextBatch();
    }

    @Override
    public boolean hasNext() {
      return !cachedBatch.isEmpty();
    }

    @Override
    public ImmutableList<T> next() {
      var toReturn = cachedBatch;
      cachedBatch = cachedBatch.size() < batchSize ? ImmutableList.of() : readNextBatch();
      return toReturn;
    }

    private ImmutableList<T> readNextBatch() {
      Optional<T> lastRead =
          cachedBatch == null
              ? Optional.empty()
              : Optional.ofNullable(Iterables.getLast(cachedBatch, null));
      return batchQuery.readBatch(lastRead, batchSize);
    }
  }
}
