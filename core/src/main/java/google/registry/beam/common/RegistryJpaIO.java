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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.backup.AppEngineEnvironment;
import google.registry.beam.common.RegistryQuery.CriteriaQuerySupplier;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.UpdateAutoTimestamp.DisableAutoUpdateResource;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaQuery;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
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
 * TransactionManagerFactory#jpaTm()}, and is shared by all threads on the VM. Configuration is
 * through {@link RegistryPipelineOptions}.
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
   * Returns a {@link Read} connector based on the given {@code jpql} query string.
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

    public static final String DEFAULT_NAME = "RegistryJpaIO.Read";

    abstract String name();

    abstract RegistryQuery<R> query();

    abstract SerializableFunction<R, T> resultMapper();

    abstract Coder<T> coder();

    abstract Builder<R, T> toBuilder();

    @Override
    @SuppressWarnings("deprecation") // Reshuffle still recommended by GCP.
    public PCollection<T> expand(PBegin input) {
      return input
          .apply("Starting " + name(), Create.of((Void) null))
          .apply("Run query for " + name(), ParDo.of(new QueryRunner<>(query(), resultMapper())))
          .setCoder(coder())
          .apply("Reshuffle", Reshuffle.viaRandomKey());
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
      return new AutoValue_RegistryJpaIO_Read.Builder<R, T>()
          .name(DEFAULT_NAME)
          .coder(SerializableCoder.of(Serializable.class));
    }

    @AutoValue.Builder
    public abstract static class Builder<R, T> {

      abstract Builder<R, T> name(String name);

      abstract Builder<R, T> query(RegistryQuery<R> query);

      abstract Builder<R, T> resultMapper(SerializableFunction<R, T> mapper);

      abstract Builder<R, T> coder(Coder coder);

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
      private final RegistryQuery<R> query;
      private final SerializableFunction<R, T> resultMapper;

      QueryRunner(RegistryQuery<R> query, SerializableFunction<R, T> resultMapper) {
        this.query = query;
        this.resultMapper = resultMapper;
      }

      @ProcessElement
      public void processElement(OutputReceiver<T> outputReceiver) {
        // AppEngineEnvironment is need for handling VKeys, which involve Ofy keys. Unlike
        // SqlBatchWriter, it is unnecessary to initialize ObjectifyService in this class.
        try (AppEngineEnvironment env = new AppEngineEnvironment()) {
          // TODO(b/187210388): JpaTransactionManager should support non-transactional query.
          jpaTm()
              .transactNoRetry(
                  () -> query.stream().map(resultMapper::apply).forEach(outputReceiver::output));
        }
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

    public static final String DEFAULT_NAME = "RegistryJpaIO.Write";

    public static final int DEFAULT_BATCH_SIZE = 1;

    /** The default number of write shard. Please refer to {@link #shards} for more information. */
    public static final int DEFAULT_SHARDS = 1;

    public abstract String name();

    /** Number of elements to be written in one call. */
    public abstract int batchSize();

    /**
     * The number of shards the output should be split into.
     *
     * <p>This value is a hint to the pipeline runner on the level of parallelism, and should be
     * significantly greater than the number of threads working on this transformation (see next
     * paragraph for more information). On the other hand, it should not be too large to the point
     * that the number of elements per shard is lower than {@link #batchSize()}. As a rule of thumb,
     * the following constraint should hold: {@code shards * batchSize * nThreads <=
     * inputElementCount}. Although it is not always possible to determine the number of threads
     * working on this transform, when the pipeline run is IO-bound, it most likely is close to the
     * total number of threads in the pipeline, which is explained below.
     *
     * <p>With Cloud Dataflow runner, the total number of worker threads in a batch pipeline (which
     * includes all existing Registry pipelines) is the number of vCPUs used by the pipeline, and
     * can be set by the {@code --maxNumWorkers} and {@code --workerMachineType} parameters. The
     * number of worker threads in a streaming pipeline can be set by the {@code --maxNumWorkers}
     * and {@code --numberOfWorkerHarnessThreads} parameters.
     *
     * <p>Note that connections on the database server are a limited resource, therefore the number
     * of threads that interact with the database should be set to an appropriate limit. Again, we
     * cannot control this number, but can influence it by controlling the total number of threads.
     */
    public abstract int shards();

    public abstract SerializableFunction<T, Object> jpaConverter();

    /**
     * Signal to the writer that the {@link UpdateAutoTimestamp} property should be written as is,
     * without pre-persist manipulation.
     *
     * <p>The default value is {@code false}.
     */
    public abstract boolean disableUpdateAutoTimestamp();

    public Write<T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Write<T> withBatchSize(int batchSize) {
      return toBuilder().batchSize(batchSize).build();
    }

    public Write<T> withShards(int shards) {
      return toBuilder().shards(shards).build();
    }

    /**
     * An optional function that converts the input entities to a form that can be written into the
     * database.
     */
    public Write<T> withJpaConverter(SerializableFunction<T, Object> jpaConverter) {
      return toBuilder().jpaConverter(jpaConverter).build();
    }

    public Write<T> withUpdateAutoTimestampDisabled() {
      return toBuilder().disableUpdateAutoTimestamp(true).build();
    }

    abstract Builder<T> toBuilder();

    @Override
    public PCollection<Void> expand(PCollection<T> input) {
      return input
          .apply(
              "Shard data " + name(),
              WithKeys.<Integer, T>of(e -> ThreadLocalRandom.current().nextInt(shards()))
                  .withKeyType(integers()))
          // The call to withShardedKey() is performance critical. The resulting transform ensures
          // that data is spread evenly across all worker threads.
          .apply(
              "Group into batches " + name(),
              GroupIntoBatches.<Integer, T>ofSize(batchSize()).withShardedKey())
          .apply(
              "Write in batch for " + name(),
              ParDo.of(new SqlBatchWriter<>(name(), jpaConverter(), disableUpdateAutoTimestamp())));
    }

    static <T> Builder<T> builder() {
      return new AutoValue_RegistryJpaIO_Write.Builder<T>()
          .name(DEFAULT_NAME)
          .batchSize(DEFAULT_BATCH_SIZE)
          .shards(DEFAULT_SHARDS)
          .jpaConverter(x -> x)
          .disableUpdateAutoTimestamp(false);
    }

    @AutoValue.Builder
    abstract static class Builder<T> {

      abstract Builder<T> name(String name);

      abstract Builder<T> batchSize(int batchSize);

      abstract Builder<T> shards(int jdbcNumConnsHint);

      abstract Builder<T> jpaConverter(SerializableFunction<T, Object> jpaConverter);

      abstract Builder<T> disableUpdateAutoTimestamp(boolean writeAsRawData);

      abstract Write<T> build();
    }
  }

  /** Writes a batch of entities to a SQL database through a {@link JpaTransactionManager}. */
  private static class SqlBatchWriter<T> extends DoFn<KV<ShardedKey<Integer>, Iterable<T>>, Void> {
    private final Counter counter;
    private final SerializableFunction<T, Object> jpaConverter;
    private final boolean writeAsRawData;

    SqlBatchWriter(
        String type, SerializableFunction<T, Object> jpaConverter, boolean writeAsRawData) {
      counter = Metrics.counter("SQL_WRITE", type);
      this.jpaConverter = jpaConverter;
      this.writeAsRawData = writeAsRawData;
    }

    @Setup
    public void setup() {
      // AppEngineEnvironment is needed as long as Objectify keys are still involved in the handling
      // of SQL entities (e.g., in VKeys). ObjectifyService needs to be initialized when conversion
      // between Ofy entity and Datastore entity is needed.
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ObjectifyService.initOfy();
      }
    }

    @ProcessElement
    public void processElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      if (writeAsRawData) {
        try (DisableAutoUpdateResource disable = UpdateAutoTimestamp.disableAutoUpdate()) {
          actuallyProcessElement(kv);
        }
        return;
      }
      actuallyProcessElement(kv);
    }

    private void actuallyProcessElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ImmutableList<Object> entities =
            Streams.stream(kv.getValue())
                .map(this.jpaConverter::apply)
                // TODO(b/177340730): post migration delete the line below.
                .filter(Objects::nonNull)
                .collect(ImmutableList.toImmutableList());
        try {
          jpaTm().transact(() -> jpaTm().putAll(entities));
          counter.inc(entities.size());
        } catch (RuntimeException e) {
          processSingly(entities);
        }
      }
    }

    /**
     * Writes entities in a failed batch one by one to identify the first bad entity and throws a
     * {@link RuntimeException} on it.
     */
    private void processSingly(ImmutableList<Object> entities) {
      for (Object entity : entities) {
        try {
          jpaTm().transact(() -> jpaTm().put(entity));
          counter.inc();
        } catch (RuntimeException e) {
          throw new RuntimeException(toEntityKeyString(entity), e);
        }
      }
    }

    private String toEntityKeyString(Object entity) {
      if (entity instanceof SqlEntity) {
        return ((SqlEntity) entity).getPrimaryKeyString();
      }
      return "Non-SqlEntity: " + String.valueOf(entity);
    }
  }
}
