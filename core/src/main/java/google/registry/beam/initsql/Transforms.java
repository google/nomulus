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

package google.registry.beam.initsql;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static google.registry.beam.initsql.BackupPaths.getCommitLogTimestamp;
import static google.registry.beam.initsql.BackupPaths.getExportFilePatterns;
import static google.registry.persistence.JpaRetries.isFailedTxnRetriable;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setJpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static java.util.Comparator.comparing;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import avro.shaded.com.google.common.collect.Iterators;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.backup.AppEngineEnvironment;
import google.registry.backup.CommitLogImports;
import google.registry.backup.VersionedEntity;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.ofy.Ofy;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.tools.LevelDbLogReader;
import google.registry.util.SystemSleeper;
import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.ProcessFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * {@link PTransform Pipeline transforms} used in pipelines that load from both Datastore export
 * files and Nomulus CommitLog files.
 */
public final class Transforms {

  private Transforms() {}

  /**
   * The commitTimestamp assigned to all entities loaded from a Datastore export file. The exact
   * value does not matter, but it must be lower than the timestamps of real CommitLog records.
   */
  @VisibleForTesting static final long EXPORT_ENTITY_TIME_STAMP = START_OF_TIME.getMillis();

  /**
   * Returns a {@link TupleTag} that can be used to retrieve entities of the given {@code kind} from
   * the Datastore snapshot returned by {@link #loadDatastoreSnapshot}.
   */
  public static TupleTag<VersionedEntity> createTagForKind(String kind) {
    // When used with PCollectionTuple the result must retain generic type information.
    // Both the Generic param and the empty bracket below are important.
    return new TupleTag<VersionedEntity>(Transforms.class.getSimpleName() + ":" + kind) {};
  }

  /**
   * Composite {@link PTransform transform} that loads the Datastore snapshot at {@code
   * commitLogToTime} for caller specified {@code kinds}.
   *
   * <p>Caller must provide the location of a Datastore export that started AFTER {@code
   * commitLogFromTime} and completed BEFORE {@code commitLogToTime}, as well as the root directory
   * of all CommitLog files.
   *
   * <p>Selection of {@code commitLogFromTime} and {@code commitLogToTime} should follow the
   * guidelines below to ensure that all incremental changes concurrent with the export are covered:
   *
   * <ul>
   *   <li>Two or more CommitLogs should exist between {@code commitLogFromTime} and the starting
   *       time of the Datastore export. This ensures that the earlier CommitLog file was complete
   *       before the export started.
   *   <li>Two or more CommitLogs should exit between the export completion time and {@code
   *       commitLogToTime}.
   * </ul>
   *
   * <p>The output from the returned transform is a {@link PCollectionTuple} consisting of {@link
   * VersionedEntity VersionedEntities} grouped into {@link PCollection PCollections} by {@code
   * kind}.
   */
  public static PTransform<PBegin, PCollectionTuple> loadDatastoreSnapshot(
      String exportDir,
      String commitLogDir,
      DateTime commitLogFromTime,
      DateTime commitLogToTime,
      Set<String> kinds) {
    checkArgument(kinds != null && !kinds.isEmpty(), "At least one kind is expected.");

    // Create tags to collect entities by kind in final step.
    final ImmutableMap<String, TupleTag<VersionedEntity>> outputTags =
        kinds.stream()
            .collect(ImmutableMap.toImmutableMap(kind -> kind, Transforms::createTagForKind));
    // Arbitrarily select one tag as mainOutTag and put the remaining ones in a TupleTagList.
    // This separation is required by ParDo's config API.
    Iterator<TupleTag<VersionedEntity>> tagsIt = outputTags.values().iterator();
    final TupleTag<VersionedEntity> mainOutputTag = tagsIt.next();
    final TupleTagList additionalTags = TupleTagList.of(ImmutableList.copyOf(tagsIt));

    return new PTransform<PBegin, PCollectionTuple>() {
      @Override
      public PCollectionTuple expand(PBegin input) {
        PCollection<VersionedEntity> exportedEntities =
            input
                .apply("Get export file patterns", getDatastoreExportFilePatterns(exportDir, kinds))
                .apply("Find export files", getFilesByPatterns())
                .apply("Load export data", loadExportDataFromFiles());
        PCollection<VersionedEntity> commitLogEntities =
            input
                .apply("Get commitlog file patterns", getCommitLogFilePatterns(commitLogDir))
                .apply("Find commitlog files", getFilesByPatterns())
                .apply(
                    "Filter commitLog by time",
                    filterCommitLogsByTime(commitLogFromTime, commitLogToTime))
                .apply("Load commitlog data", loadCommitLogsFromFiles(kinds));
        return PCollectionList.of(exportedEntities)
            .and(commitLogEntities)
            .apply("Merge exports and CommitLogs", Flatten.pCollections())
            .apply(
                "Key entities by Datastore Keys",
                // Converting to KV<String, VE> instead of KV<Key, VE> b/c default coder for Key
                // (SerializableCoder) is not deterministic and cannot be used with GroupBy.
                MapElements.into(kvs(strings(), TypeDescriptor.of(VersionedEntity.class)))
                    .via((VersionedEntity e) -> KV.of(e.key().toString(), e)))
            .apply("Gather entities by key", GroupByKey.create())
            .apply(
                "Output latest version per entity",
                ParDo.of(
                        new DoFn<KV<String, Iterable<VersionedEntity>>, VersionedEntity>() {
                          @ProcessElement
                          public void processElement(
                              @Element KV<String, Iterable<VersionedEntity>> kv,
                              MultiOutputReceiver out) {
                            Optional<VersionedEntity> latest =
                                Streams.stream(kv.getValue())
                                    .sorted(comparing(VersionedEntity::commitTimeMills).reversed())
                                    .findFirst();
                            // Throw to abort (after default retries). Investigate, fix, and rerun.
                            checkState(
                                latest.isPresent(), "Unexpected key with no data", kv.getKey());
                            if (latest.get().isDelete()) {
                              return;
                            }
                            String kind = latest.get().getEntity().get().getKind();
                            out.get(outputTags.get(kind)).output(latest.get());
                          }
                        })
                    .withOutputTags(mainOutputTag, additionalTags));
      }
    };
  }

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore CommitLog files.
   */
  public static PTransform<PBegin, PCollection<String>> getCommitLogFilePatterns(
      String commitLogDir) {
    return toStringPCollection(BackupPaths.getCommitLogFilePatterns(commitLogDir));
  }

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore export files of the given {@code kinds}.
   */
  public static PTransform<PBegin, PCollection<String>> getDatastoreExportFilePatterns(
      String exportDir, Collection<String> kinds) {
    return toStringPCollection(getExportFilePatterns(exportDir, kinds));
  }

  public static PTransform<PBegin, PCollection<String>> getCloudSqlConnectionInfoFilePatterns(
      String gcpProjectName) {
    return toStringPCollection(BackupPaths.getCloudSQLCredentialFilePatterns(gcpProjectName));
  }

  /**
   * Returns a {@link PTransform} from file name patterns to file {@link Metadata Metadata records}.
   */
  public static PTransform<PCollection<String>, PCollection<Metadata>> getFilesByPatterns() {
    return new PTransform<PCollection<String>, PCollection<Metadata>>() {
      @Override
      public PCollection<Metadata> expand(PCollection<String> input) {
        return input.apply(FileIO.matchAll().withEmptyMatchTreatment(EmptyMatchTreatment.ALLOW));
      }
    };
  }

  /**
   * Returns CommitLog files with timestamps between {@code fromTime} (inclusive) and {@code
   * endTime} (exclusive).
   */
  public static PTransform<PCollection<? extends Metadata>, PCollection<Metadata>>
      filterCommitLogsByTime(DateTime fromTime, DateTime toTime) {
    return ParDo.of(new FilterCommitLogFileByTime(fromTime, toTime));
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadExportDataFromFiles() {
    return processFiles(
        new BackupFileReader(
            file ->
                Iterators.transform(
                    LevelDbLogReader.from(file.open()),
                    (byte[] bytes) -> VersionedEntity.from(EXPORT_ENTITY_TIME_STAMP, bytes))));
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadCommitLogsFromFiles(Set<String> kinds) {
    return processFiles(
        new BackupFileReader(
            file ->
                CommitLogImports.loadEntities(file.open()).stream()
                    .filter(e -> kinds.contains(e.key().getKind()))
                    .iterator()));
  }

  /**
   * Returns a {@link PTransform} that writes a {@link PCollection} of entities to a SQL database.
   * and outputs an empty {@code PCollection<Void>}. This allows other operations to {@link
   * org.apache.beam.sdk.transforms.Wait wait} for the completion of this transform.
   *
   * <p>Errors are handled according to the pipeline runner's default policy. As part of a one-time
   * job, we will not add features unless proven necessary.
   *
   * @param transformId a unique ID for an instance of the returned transform
   * @param maxWriters the max number of concurrent writes to SQL, which also determines the max
   *     number of connection pools created
   * @param batchSize the number of entities to write in each operation
   * @param jpaSupplier supplier of a {@link JpaTransactionManager}
   */
  public static PTransform<PCollection<VersionedEntity>, PCollection<Void>> writeToSql(
      String transformId,
      int maxWriters,
      int batchSize,
      SerializableSupplier<JpaTransactionManager> jpaSupplier) {
    return new PTransform<PCollection<VersionedEntity>, PCollection<Void>>() {
      @Override
      public PCollection<Void> expand(PCollection<VersionedEntity> input) {
        return input
            .apply(
                "Shard data for " + transformId,
                MapElements.into(kvs(integers(), TypeDescriptor.of(VersionedEntity.class)))
                    .via(ve -> KV.of(ThreadLocalRandom.current().nextInt(maxWriters), ve)))
            .apply("Batch output by shard " + transformId, GroupIntoBatches.ofSize(batchSize))
            .apply("Write in batch for " + transformId, ParDo.of(new SqlBatchWriter(jpaSupplier)));
      }
    };
  }

  /** Interface for serializable {@link Supplier suppliers}. */
  public interface SerializableSupplier<T> extends Supplier<T>, Serializable {}

  /**
   * Returns a {@link PTransform} that produces a {@link PCollection} containing all elements in the
   * given {@link Iterable}.
   */
  static PTransform<PBegin, PCollection<String>> toStringPCollection(Iterable<String> strings) {
    return Create.of(strings).withCoder(StringUtf8Coder.of());
  }

  /**
   * Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity} using
   * caller-provided {@code transformer}.
   */
  static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>> processFiles(
      DoFn<ReadableFile, VersionedEntity> transformer) {
    return new PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>() {
      @Override
      public PCollection<VersionedEntity> expand(PCollection<Metadata> input) {
        return input
            .apply(FileIO.readMatches().withCompression(Compression.UNCOMPRESSED))
            .apply(transformer.getClass().getSimpleName(), ParDo.of(transformer));
      }
    };
  }

  private static class FilterCommitLogFileByTime extends DoFn<Metadata, Metadata> {
    private final DateTime fromTime;
    private final DateTime toTime;

    public FilterCommitLogFileByTime(DateTime fromTime, DateTime toTime) {
      checkNotNull(fromTime, "fromTime");
      checkNotNull(toTime, "toTime");
      checkArgument(
          fromTime.isBefore(toTime),
          "Invalid time range: fromTime (%s) is before endTime (%s)",
          fromTime,
          toTime);
      this.fromTime = fromTime;
      this.toTime = toTime;
    }

    @ProcessElement
    public void processElement(@Element Metadata fileMeta, OutputReceiver<Metadata> out) {
      DateTime timestamp = getCommitLogTimestamp(fileMeta.resourceId().toString());
      if (isBeforeOrAt(fromTime, timestamp) && timestamp.isBefore(toTime)) {
        out.output(fileMeta);
      }
    }
  }

  /**
   * Reads from a Datastore backup file and converts its content into {@link VersionedEntity
   * VersionedEntities}.
   *
   * <p>The input file may be either a LevelDb file from a Datastore export or a CommitLog file
   * generated by the Nomulus server. In either case, the file contains variable-length records and
   * must be read sequentially from the beginning. If the read fails, the file needs to be retried
   * from the beginning.
   */
  private static class BackupFileReader extends DoFn<ReadableFile, VersionedEntity> {
    private final ProcessFunction<ReadableFile, Iterator<VersionedEntity>> reader;

    private BackupFileReader(ProcessFunction<ReadableFile, Iterator<VersionedEntity>> reader) {
      this.reader = reader;
    }

    @ProcessElement
    public void processElement(@Element ReadableFile file, OutputReceiver<VersionedEntity> out) {
      try {
        reader.apply(file).forEachRemaining(out::output);
      } catch (Exception e) {
        // Let the pipeline use default retry strategy on the whole file. For GCP Dataflow this
        // means retrying up to 4 times (may include other files grouped with this one), and failing
        // the pipeline if no success.
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Writes a batch of entities to a SQL database.
   *
   * <p>Note that an arbitrary number of instances of this class may be created and freed in
   * arbitrary order in a single JVM. Due to the tech debt that forced us to use a static variable
   * to hold the {@code JpaTransactionManager} instance, we must ensure that JpaTransactionManager
   * is not changed or torn down while being used by some instance.
   */
  private static class SqlBatchWriter extends DoFn<KV<Integer, Iterable<VersionedEntity>>, Void> {

    private static int instanceCount = 0;
    private static JpaTransactionManager originalJpa;

    private final SerializableSupplier<JpaTransactionManager> jpaSupplier;

    private transient Ofy ofy;
    private transient SystemSleeper sleeper;

    SqlBatchWriter(SerializableSupplier<JpaTransactionManager> jpaSupplier) {
      this.jpaSupplier = jpaSupplier;
    }

    @Setup
    public void setup() {
      sleeper = new SystemSleeper();

      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ObjectifyService.initOfy();
        ofy = ObjectifyService.ofy();
      }

      synchronized (SqlBatchWriter.class) {
        if (instanceCount == 0) {
          originalJpa = jpaTm();
          setJpaTm(jpaSupplier);
        }
        instanceCount++;
      }
    }

    @Teardown
    public void teardown() {
      synchronized (SqlBatchWriter.class) {
        instanceCount--;
        if (instanceCount == 0) {
          jpaTm().teardown();
          setJpaTm(() -> originalJpa);
        }
      }
    }

    @ProcessElement
    public void processElement(@Element KV<Integer, Iterable<VersionedEntity>> kv) {
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ImmutableList<Object> ofyEntities =
            Streams.stream(kv.getValue())
                .map(VersionedEntity::getEntity)
                .map(Optional::get)
                .map(ofy::toPojo)
                .collect(ImmutableList.toImmutableList());
        retry(() -> jpaTm().transact(() -> jpaTm().saveNewOrUpdateAll(ofyEntities)));
      }
    }

    // TODO(b/160632289): Enhance Retrier and use it here.
    private void retry(Runnable runnable) {
      int maxAttempts = 5;
      int initialDelayMillis = 100;
      double jitterRatio = 0.2;

      for (int attempt = 0; attempt < maxAttempts; attempt++) {
        try {
          runnable.run();
          return;
        } catch (Throwable throwable) {
          if (!isFailedTxnRetriable(throwable)) {
            throwIfUnchecked(throwable);
            throw new RuntimeException(throwable);
          }
          int sleepMillis = (1 << attempt) * initialDelayMillis;
          int jitter =
              ThreadLocalRandom.current().nextInt((int) (sleepMillis * jitterRatio))
                  - (int) (sleepMillis * jitterRatio / 2);
          sleeper.sleepUninterruptibly(Duration.millis(sleepMillis + jitter));
        }
      }
    }
  }

  /**
   * Removes BillingEvents, {@link google.registry.model.poll.PollMessage PollMessages} and {@link
   * google.registry.model.host.HostResource} from a {@link DomainBase}. These are circular foreign
   * key constraints that prevent migration of {@code DomainBase} to SQL databases.
   *
   * <p>See {@link InitSqlPipeline} for more information.
   */
  static class RemoveDomainBaseForeignKeys extends DoFn<VersionedEntity, VersionedEntity> {

    @ProcessElement
    public void processElement(
        @Element VersionedEntity domainBase, OutputReceiver<VersionedEntity> out) {
      checkArgument(
          domainBase.getEntity().isPresent(), "Unexpected delete entity %s", domainBase.key());
      Entity outputEntity =
          DomainBaseUtil.removeBillingAndPollAndHosts(domainBase.getEntity().get());
      out.output(
          VersionedEntity.from(
              domainBase.commitTimeMills(),
              EntityTranslator.convertToPb(outputEntity).toByteArray()));
    }
  }
}
