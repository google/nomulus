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

package google.registry.beam.datastore;

import static com.google.common.base.Preconditions.checkState;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.FluentLogger;
import com.google.datastore.v1.Entity;
import java.util.Iterator;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.datastore.DatastoreIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;

/**
 * A BEAM pipeline that deletes Datastore entities in bulk.
 *
 * <p>This pipeline provides an alternative to the <a
 * href="https://cloud.google.com/datastore/docs/bulk-delete">GCP builtin template</a> that performs
 * the same task. It solves the following performance and usability problems in the builtin
 * template:
 *
 * <ul>
 *   <li>When deleting all data (by using the {@code select __key__} or {@code select *} queries),
 *       the builtin template cannot parallelize the query, therefore has to query with a single
 *       worker.
 *   <li>When deleting all data, the builtin template also attempts to delete Datastore internal
 *       tables which would cause permission-denied errors, which in turn MAY cause the pipeline to
 *       abort before all data has been deleted.
 *   <li>With the builtin template, it is possible to delete multiple entity types in one pipeline
 *       ONLY if the user can come up with a single literal query that covers all of them. This is
 *       not the case with most Nomulus entity types.
 * </ul>
 *
 * <p>A user of this pipeline must specify the types of entities to delete using the {@code
 * --kindsToDelete} command line argument. To delete specific entity types, give a comma-separated
 * string of their kind names; to delete all data, give {@code "*"}.
 *
 * <p>When deleting all data, it is recommended for the user to specify the number of user entity
 * types in the Datastore using the {@code --numOfKindsHint} argument. If the default value for this
 * parameter is too low, performance will suffer.
 */
public class BulkDeletePipeline {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // This tool is not for use in our critical projects.
  private static final ImmutableSet<String> FORBIDDEN_PROJECTS =
      ImmutableSet.of("domain-registry", "domain-registry-sandbox");

  private final BulkDeletePipelineOptions options;

  private final Pipeline pipeline;

  BulkDeletePipeline(BulkDeletePipelineOptions options) {
    this.options = options;
    pipeline = Pipeline.create(options);
  }

  public void run() {
    setupPipeline();
    pipeline.run();
  }

  @SuppressWarnings("deprecation") // org.apache.beam.sdk.transforms.Reshuffle
  private void setupPipeline() {
    checkState(
        !FORBIDDEN_PROJECTS.contains(options.getProject()),
        "Bulk delete is forbidden in %s",
        options.getProject());

    // Pre-allocated tags to label entities by kind. In the case of delete-all, we must use a guess.
    TupleTagList deletionTags;
    PCollection<String> kindsToDelete;

    if (options.getKindsToDelete().equals("*")) {
      deletionTags = getDeletionTags(options.getNumOfKindsHint());
      kindsToDelete =
          pipeline.apply("DiscoverEntityKinds", discoverEntityKinds(options.getProject()));
    } else {
      ImmutableList<String> kindsToDeleteParam = parseKindsToDelete(options);
      deletionTags = getDeletionTags(kindsToDeleteParam.size());
      kindsToDelete = pipeline.apply("UseProvidedKinds", Create.of(kindsToDeleteParam));
    }

    // Map each kind to a tag. The "SplitByKind" stage below will group entities by kind using
    // this mapping. In practice, this has been effective at avoiding entity group contentions.
    PCollectionView<Map<String, TupleTag<Entity>>> kindToTagMapping =
        mapKindsToDeletionTags(kindsToDelete, deletionTags).apply("GetKindsToTagMap", View.asMap());

    PCollectionTuple entities =
        kindsToDelete
            .apply("GenerateQueries", ParDo.of(new GenerateQueries()))
            .apply("ReadEntities", DatastoreV1.read().withProjectId(options.getProject()))
            .apply(
                "SplitByKind",
                ParDo.of(new SplitEntities(kindToTagMapping))
                    .withSideInputs(kindToTagMapping)
                    .withOutputTags(getOneDeletionTag("placeholder"), deletionTags));

    for (TupleTag<?> tag : deletionTags.getAll()) {
      entities
          .get((TupleTag<Entity>) tag)
          // Reshuffle calls GroupByKey which is one way to trigger load rebalance in the pipeline.
          // Using the deprecated "Reshuffle" for convenience given the short life of this tool.
          .apply("RebalanceLoad", Reshuffle.viaRandomKey())
          .apply(
              "DeleteEntities_" + tag.getId(),
              DatastoreIO.v1().deleteEntity().withProjectId(options.getProject()));
    }
  }

  private static String toKeyOnlyQueryForKind(String kind) {
    return "select __key__ from `" + kind + "`";
  }

  /**
   * Returns a {@link TupleTag} that retains the generic type parameter and may be used in a
   * multi-output {@link ParDo} (e.g. {@link SplitEntities}).
   *
   * <p>This method is NOT needed in tests when creating tags for assertions. Simply create them
   * with {@code new TupleTag<Entity>(String)}.
   */
  @VisibleForTesting
  static TupleTag<Entity> getOneDeletionTag(String id) {
    // The trailing {} is needed to retain generic param type.
    return new TupleTag<Entity>(id) {};
  }

  @VisibleForTesting
  static ImmutableList<String> parseKindsToDelete(BulkDeletePipelineOptions options) {
    return ImmutableList.copyOf(
        Splitter.on(",").omitEmptyStrings().trimResults().split(options.getKindsToDelete().trim()));
  }

  /**
   * Returns a list of {@code n} {@link TupleTag TupleTags} numbered from {@code 0} to {@code n-1}.
   */
  @VisibleForTesting
  static TupleTagList getDeletionTags(int n) {
    ImmutableList.Builder<TupleTag<?>> builder = new ImmutableList.Builder<>();
    for (int i = 0; i < n; i++) {
      builder.add(getOneDeletionTag(String.valueOf(i)));
    }
    return TupleTagList.of(builder.build());
  }

  /** Returns a {@link PTransform} that finds all entity kinds in Datastore. */
  @VisibleForTesting
  static PTransform<PBegin, PCollection<String>> discoverEntityKinds(String project) {
    return new PTransform<PBegin, PCollection<String>>() {
      @Override
      public PCollection<String> expand(PBegin input) {
        // Use the __kind__ table to discover entity kinds. Data in the more informational
        // __Stat_Kind__ table may be up to 48-hour stale.
        return input
            .apply(
                "LoadEntityMetaData",
                DatastoreIO.v1()
                    .read()
                    .withProjectId(project)
                    .withLiteralGqlQuery("select * from __kind__"))
            .apply(
                "GetKindNames",
                ParDo.of(
                    new DoFn<Entity, String>() {
                      @ProcessElement
                      public void processElement(
                          @Element Entity entity, OutputReceiver<String> out) {
                        String kind = entity.getKey().getPath(0).getName();
                        if (kind.startsWith("_")) {
                          return;
                        }
                        out.output(kind);
                      }
                    }));
      }
    };
  }

  @VisibleForTesting
  static PCollection<KV<String, TupleTag<Entity>>> mapKindsToDeletionTags(
      PCollection<String> kinds, TupleTagList tags) {
    return kinds
        .apply(
            "AssignSingletonKeyToKinds",
            MapElements.into(kvs(strings(), strings())).via(kind -> KV.of("", kind)))
        .apply("GatherKindsIntoCollection", GroupByKey.create())
        .apply("MapKindsToTag", ParDo.of(new MapKindsToTags(tags)));
  }

  /** Transforms each {@code kind} string into a Datastore query for that kind. */
  @VisibleForTesting
  static class GenerateQueries extends DoFn<String, String> {
    @ProcessElement
    public void processElement(@Element String kind, OutputReceiver<String> out) {
      out.output(toKeyOnlyQueryForKind(kind));
    }
  }

  private static class MapKindsToTags
      extends DoFn<KV<String, Iterable<String>>, KV<String, TupleTag<Entity>>> {
    private final TupleTagList tupleTags;

    MapKindsToTags(TupleTagList tupleTags) {
      this.tupleTags = tupleTags;
    }

    @ProcessElement
    public void processElement(
        @Element KV<String, Iterable<String>> kv,
        OutputReceiver<KV<String, TupleTag<Entity>>> out) {
      // Sort kinds so that mapping is deterministic.
      ImmutableSortedSet<String> sortedKinds = ImmutableSortedSet.copyOf(kv.getValue());
      Iterator<String> kinds = sortedKinds.iterator();
      Iterator<TupleTag<?>> tags = tupleTags.getAll().iterator();

      while (kinds.hasNext() && tags.hasNext()) {
        out.output(KV.of(kinds.next(), (TupleTag<Entity>) tags.next()));
      }

      if (kinds.hasNext()) {
        logger.atWarning().log(
            "There are more kinds to delete (%s) than our estimate (%s). "
                + "Performance may suffer.",
            sortedKinds.size(), tupleTags.size());
      }
      // Round robin assignment so that mapping is deterministic
      while (kinds.hasNext()) {
        tags = tupleTags.getAll().iterator();
        while (kinds.hasNext() && tags.hasNext()) {
          out.output(KV.of(kinds.next(), (TupleTag<Entity>) tags.next()));
        }
      }
    }
  }

  /**
   * {@link DoFn} that splits one {@link PCollection} of mixed kinds into multiple single-kind
   * {@code PCollections}.
   */
  @VisibleForTesting
  static class SplitEntities extends DoFn<Entity, Entity> {
    private final PCollectionView<Map<String, TupleTag<Entity>>> kindToTagMapping;

    SplitEntities(PCollectionView<Map<String, TupleTag<Entity>>> kindToTagMapping) {
      super();
      this.kindToTagMapping = kindToTagMapping;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      Entity entity = context.element();
      com.google.datastore.v1.Key entityKey = entity.getKey();
      String kind = entityKey.getPath(entityKey.getPathCount() - 1).getKind();
      TupleTag<Entity> tag = context.sideInput(kindToTagMapping).get(kind);
      context.output(tag, entity);
    }
  }

  public static void main(String[] args) {
    BulkDeletePipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(BulkDeletePipelineOptions.class);
    BulkDeletePipeline pipeline = new BulkDeletePipeline(options);
    pipeline.run();
    System.exit(0);
  }

  public interface BulkDeletePipelineOptions extends GcpOptions {

    @Description(
        "The Datastore KINDs to be deleted. The format may be:\n"
            + "\t- The list of kinds to be deleted as a comma-separated string, or\n"
            + "\t- '*', which causes all kinds to be deleted.")
    @Validation.Required
    String getKindsToDelete();

    void setKindsToDelete(String kinds);

    @Description(
        "An estimate of the number of KINDs to be deleted. "
            + "This is recommended if --kindsToDelete is '*' and the default value is too low.")
    @Default.Integer(30)
    int getNumOfKindsHint();

    void setNumOfKindsHint(int numOfKindsHint);
  }
}
