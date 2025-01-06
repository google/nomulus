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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.beam.common.RegistryQuery.CriteriaQuerySupplier;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;

/**
 * Contains IO {@link PTransform transforms} for a BEAM pipeline that interacts with a single
 * database through a {@link JpaTransactionManager}.
 *
 * <p>The {@code JpaTransactionManager} is instantiated once on each pipeline worker VM (through
 * {@link RegistryPipelineWorkerInitializer}), made available through the static method {@link
 * TransactionManagerFactory#tm()}, and is shared by all threads on the VM. Configuration is through
 * {@link RegistryPipelineOptions}.
 */
public final class RegistryJpaIO {

  private RegistryJpaIO() {}

  public static <R> Read<R, R> read(CriteriaQuerySupplier<R> query) {
    return read(query, x -> x);
  }

  public static <R, T> Read<R, T> read(
      CriteriaQuerySupplier<R> query, SerializableFunction<R, T> resultMapper) {
    return Read.<R, T>builder().criteriaQuery(query).resultMapper(resultMapper).build();
  }

  public static <R, T> Read<R, T> read(
      String sql, boolean nativeQuery, SerializableFunction<R, T> resultMapper) {
    return read(sql, null, nativeQuery, resultMapper);
  }

  /**
   * Returns a {@link Read} connector based on the given native or {@code jpql} query string.
   *
   * <p>User should take care to prevent sql-injection attacks.
   */
  public static <R, T> Read<R, T> read(
      String sql,
      @Nullable Map<String, Object> parameter,
      boolean nativeQuery,
      SerializableFunction<R, T> resultMapper) {
    Read.Builder<R, T> builder = Read.builder();
    if (nativeQuery) {
      builder.nativeQuery(sql, parameter);
    } else {
      builder.jpqlQuery(sql, parameter);
    }
    return builder.resultMapper(resultMapper).build();
  }

  public static <R, T> Read<R, T> read(
      String jpql, Class<R> clazz, SerializableFunction<R, T> resultMapper) {
    return read(jpql, null, clazz, resultMapper);
  }

  /**
   * Returns a {@link Read} connector based on the given {@code jpql} typed query string.
   *
   * <p>User should take care to prevent sql-injection attacks.
   */
  public static <R, T> Read<R, T> read(
      String jpql,
      @Nullable Map<String, Object> parameter,
      Class<R> clazz,
      SerializableFunction<R, T> resultMapper) {
    return Read.<R, T>builder()
        .jpqlQuery(jpql, clazz, parameter)
        .resultMapper(resultMapper)
        .build();
  }

  public static <T> Write<T> write() {
    return Write.<T>builder().build();
  }

  /**
   * A {@link PTransform transform} that transactionally executes a JPA {@link CriteriaQuery} and
   * adds the results to the BEAM pipeline. Users have the option to transform the results before
   * sending them to the next stages.
   */
  @AutoValue
  public abstract static class Read<R, T> extends PTransform<PBegin, PCollection<T>> {

    private static final long serialVersionUID = 6906842877429561700L;
    public static final String DEFAULT_NAME = "RegistryJpaIO.Read";

    abstract String name();

    abstract RegistryQuery<R> query();

    abstract SerializableFunction<R, T> resultMapper();

    @Nullable
    abstract Coder<T> coder();

    abstract Builder<R, T> toBuilder();

    @Override
    @SuppressWarnings("deprecation") // Reshuffle still recommended by GCP.
    public PCollection<T> expand(PBegin input) {
      PCollection<T> output =
          input
              .apply("Starting " + name(), Create.of((Void) null))
              .apply(
                  "Run query for " + name(), ParDo.of(new QueryRunner<>(query(), resultMapper())));
      if (coder() != null) {
        output = output.setCoder(coder());
      }
      return output.apply("Reshuffle", Reshuffle.viaRandomKey());
    }

    public Read<R, T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Read<R, T> withResultMapper(SerializableFunction<R, T> mapper) {
      return toBuilder().resultMapper(mapper).build();
    }

    public Read<R, T> withCoder(Coder<T> coder) {
      return toBuilder().coder(coder).build();
    }

    static <R, T> Builder<R, T> builder() {
      return new AutoValue_RegistryJpaIO_Read.Builder<R, T>().name(DEFAULT_NAME);
    }

    @AutoValue.Builder
    public abstract static class Builder<R, T> {

      abstract Builder<R, T> name(String name);

      abstract Builder<R, T> query(RegistryQuery<R> query);

      abstract Builder<R, T> resultMapper(SerializableFunction<R, T> mapper);

      abstract Builder<R, T> coder(Coder<T> coder);

      abstract Read<R, T> build();

      Builder<R, T> criteriaQuery(CriteriaQuerySupplier<R> criteriaQuery) {
        return query(RegistryQuery.createQuery(criteriaQuery));
      }

      Builder<R, T> nativeQuery(String sql, Map<String, Object> parameters) {
        return query(RegistryQuery.createQuery(sql, parameters, true));
      }

      Builder<R, T> jpqlQuery(String jpql, Map<String, Object> parameters) {
        return query(RegistryQuery.createQuery(jpql, parameters, false));
      }

      Builder<R, T> jpqlQuery(String jpql, Class<R> clazz, Map<String, Object> parameters) {
        return query(RegistryQuery.createQuery(jpql, parameters, clazz));
      }
    }

    static class QueryRunner<R, T> extends DoFn<Void, T> {

      private static final long serialVersionUID = 7293891513058653334L;
      private final RegistryQuery<R> query;
      private final SerializableFunction<R, T> resultMapper;

      QueryRunner(RegistryQuery<R> query, SerializableFunction<R, T> resultMapper) {
        this.query = query;
        this.resultMapper = resultMapper;
      }

      @ProcessElement
      public void processElement(OutputReceiver<T> outputReceiver) {
        // Note the use of no-retry transaction here. The results from the query are streamed to the
        // output receiver inside the transaction, which cannot be rolled back in case of a retry,
        // which in turn results in duplicate elements. If we try to pass the results to the output
        // receiver outside the transaction, they have to be materialized into a list containing all
        // the elements (without resorting to manual pagination) and greatly decrease the
        // parallelism of the pipeline.
        tm().transactNoRetry(
                () -> {
                  query.stream().map(resultMapper::apply).forEach(outputReceiver::output);
                  return null;
                });
      }
    }
  }

  /**
   * A {@link PTransform transform} that writes a PCollection of entities to the SQL database using
   * the {@link JpaTransactionManager}.
   *
   * <p>Unlike typical BEAM {@link Write} transforms, the output type of this transform is {@code
   * PCollection<Void>} instead of {@link org.apache.beam.sdk.values.PDone}. This deviation allows
   * the sequencing of multiple {@code PCollections}: we have use cases where one collection of data
   * must be completely written before another can start (due to foreign key constraints in the
   * latter).
   *
   * @param <T> type of the entities to be written
   */
  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, PCollection<Void>> {

    private static final long serialVersionUID = -4023583243078410323L;
    public static final String DEFAULT_NAME = "RegistryJpaIO.Write";
    public static final int DEFAULT_BATCH_SIZE = 1;

    public abstract String name();

    /** Number of elements to be written in one call. */
    public abstract int batchSize();

    public abstract SerializableFunction<T, Object> jpaConverter();

    public Write<T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Write<T> withBatchSize(int batchSize) {
      return toBuilder().batchSize(batchSize).build();
    }

    /**
     * An optional function that converts the input entities to a form that can be written into the
     * database.
     */
    public Write<T> withJpaConverter(SerializableFunction<T, Object> jpaConverter) {
      return toBuilder().jpaConverter(jpaConverter).build();
    }

    abstract Builder<T> toBuilder();

    @Override
    public PCollection<Void> expand(PCollection<T> input) {
      return input
          .apply("Add key to data " + name(), WithKeys.<Integer, T>of(0).withKeyType(integers()))
          // The call to withShardedKey() is performance critical. The resulting transform ensures
          // that data is spread evenly across all worker threads.
          .apply(
              "Group into batches " + name(),
              GroupIntoBatches.<Integer, T>ofSize(batchSize()).withShardedKey())
          .apply(
              "Write in batch for " + name(),
              ParDo.of(new SqlBatchWriter<>(name(), jpaConverter())));
    }

    static <T> Builder<T> builder() {
      return new AutoValue_RegistryJpaIO_Write.Builder<T>()
          .name(DEFAULT_NAME)
          .batchSize(DEFAULT_BATCH_SIZE)
          .jpaConverter(x -> x);
    }

    @AutoValue.Builder
    abstract static class Builder<T> {

      abstract Builder<T> name(String name);

      abstract Builder<T> batchSize(int batchSize);

      abstract Builder<T> jpaConverter(SerializableFunction<T, Object> jpaConverter);

      abstract Write<T> build();
    }
  }

  /** Writes a batch of entities to a SQL database through a {@link JpaTransactionManager}. */
  private static class SqlBatchWriter<T> extends DoFn<KV<ShardedKey<Integer>, Iterable<T>>, Void> {

    private static final long serialVersionUID = -7519944406319472690L;
    private final Counter counter;
    private final SerializableFunction<T, Object> jpaConverter;

    SqlBatchWriter(String type, SerializableFunction<T, Object> jpaConverter) {
      counter = Metrics.counter("SQL_WRITE", type);
      this.jpaConverter = jpaConverter;
    }

    @ProcessElement
    public void processElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      actuallyProcessElement(kv);
    }

    private void actuallyProcessElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      ImmutableList<Object> entities =
          Streams.stream(kv.getValue())
              .map(jpaConverter::apply)
              // TODO(b/177340730): post migration delete the line below.
              .filter(Objects::nonNull)
              .collect(ImmutableList.toImmutableList());
      try {
        tm().transact(
                () -> {
                  // TODO(b/263502442): properly handle creations and blind-writes.
                  tm().putAll(entities);
                });
        counter.inc(entities.size());
      } catch (RuntimeException e) {
        processSingly(entities);
      }
    }

    /**
     * Writes entities in a failed batch one by one to identify the first bad entity and throws a
     * {@link RuntimeException} on it.
     */
    private void processSingly(ImmutableList<Object> entities) {
      for (Object entity : entities) {
        try {
          tm().transact(
                  () -> {
                    // TODO(b/263502442): properly handle creations and blind-writes.
                    tm().put(entity);
                  });
          counter.inc();
        } catch (RuntimeException e) {
          throw new RuntimeException(toEntityKeyString(entity), e);
        }
      }
    }

    /** Returns this entity's primary key field(s) in a string. */
    private static String toEntityKeyString(Object entity) {
      try {
        return tm().transact(
                () ->
                    String.format(
                        "%s_%s",
                        entity.getClass().getSimpleName(),
                        tm().getEntityManager()
                            .getEntityManagerFactory()
                            .getPersistenceUnitUtil()
                            .getIdentifier(entity)));
      } catch (IllegalArgumentException e) {
        return "Non-SqlEntity: " + entity;
      }
    }
  }
}
