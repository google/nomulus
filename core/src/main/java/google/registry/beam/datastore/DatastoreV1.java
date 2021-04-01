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

// This class is adapted from the Apache BEAM SDK. The original license may
// be found at <a href="https://github.com/apache/beam/blob/master/LICENSE">
// this link</a>.

package google.registry.beam.datastore;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.datastore.v1.PropertyFilter.Operator.EQUAL;
import static com.google.datastore.v1.PropertyOrder.Direction.DESCENDING;
import static com.google.datastore.v1.QueryResultBatch.MoreResultsType.NOT_FINISHED;
import static com.google.datastore.v1.client.DatastoreHelper.makeAndFilter;
import static com.google.datastore.v1.client.DatastoreHelper.makeFilter;
import static com.google.datastore.v1.client.DatastoreHelper.makeOrder;
import static com.google.datastore.v1.client.DatastoreHelper.makeValue;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auto.value.AutoValue;
import com.google.cloud.hadoop.util.ChainingHttpRequestInitializer;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.EntityResult;
import com.google.datastore.v1.GqlQuery;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.QueryResultBatch;
import com.google.datastore.v1.RunQueryRequest;
import com.google.datastore.v1.RunQueryResponse;
import com.google.datastore.v1.client.Datastore;
import com.google.datastore.v1.client.DatastoreException;
import com.google.datastore.v1.client.DatastoreFactory;
import com.google.datastore.v1.client.DatastoreHelper;
import com.google.datastore.v1.client.DatastoreOptions;
import com.google.datastore.v1.client.QuerySplitter;
import com.google.protobuf.Int32Value;
import com.google.rpc.Code;
import java.io.Serializable;
import java.util.List;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.extensions.gcp.util.RetryHttpRequestInitializer;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.BackOffUtils;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Sleeper;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

/**
 * Contains an adaptation of {@link org.apache.beam.sdk.io.gcp.datastore.DatastoreV1.Read}. See
 * {@link MultiRead} for details.
 */
public class DatastoreV1 {

  // A package-private constructor to prevent direct instantiation from outside of this package
  DatastoreV1() {}

  /**
   * Non-retryable errors. See https://cloud.google.com/datastore/docs/concepts/errors#Error_Codes .
   */
  private static final ImmutableSet<Code> NON_RETRYABLE_ERRORS =
      ImmutableSet.of(
          Code.FAILED_PRECONDITION,
          Code.INVALID_ARGUMENT,
          Code.PERMISSION_DENIED,
          Code.UNAUTHENTICATED);

  /**
   * Returns an empty {@link MultiRead} builder. Configure the source {@code projectId}, {@code
   * query}, and optionally {@code namespace} and {@code numQuerySplits} using {@link
   * MultiRead#withProjectId}, {@link MultiRead#withNamespace}, {@link
   * MultiRead#withNumQuerySplits}.
   */
  public static MultiRead read() {
    return new AutoValue_DatastoreV1_MultiRead.Builder().setNumQuerySplits(0).build();
  }

  /**
   * A {@link PTransform} that executes every Cloud SQL queries in a {@link PCollection } and reads
   * their result rows as {@code Entity} objects.
   *
   * <p>This class is adapted from {@link org.apache.beam.sdk.io.gcp.datastore.DatastoreV1.Read}. It
   * uses literal GQL queries in the input {@link PCollection} instead of a constant query provided
   * to the builder. Only the {@link #expand} method is modified from the original. Everything else
   * including comments have been copied verbatim.
   */
  @AutoValue
  public abstract static class MultiRead
      extends PTransform<PCollection<String>, PCollection<Entity>> {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    /** An upper bound on the number of splits for a query. */
    public static final int NUM_QUERY_SPLITS_MAX = 50000;

    /** A lower bound on the number of splits for a query. */
    static final int NUM_QUERY_SPLITS_MIN = 12;

    /** Default bundle size of 64MB. */
    static final long DEFAULT_BUNDLE_SIZE_BYTES = 64L * 1024L * 1024L;

    /**
     * Maximum number of results to request per query.
     *
     * <p>Must be set, or it may result in an I/O error when querying Cloud Datastore.
     */
    static final int QUERY_BATCH_LIMIT = 500;

    public abstract @Nullable String getProjectId();

    public abstract @Nullable String getNamespace();

    public abstract int getNumQuerySplits();

    public abstract @Nullable String getLocalhost();

    @Override
    public abstract String toString();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setProjectId(String projectId);

      abstract Builder setNamespace(String namespace);

      abstract Builder setNumQuerySplits(int numQuerySplits);

      abstract Builder setLocalhost(String localhost);

      abstract MultiRead build();
    }

    /**
     * Computes the number of splits to be performed on the given query by querying the estimated
     * size from Cloud Datastore.
     */
    static int getEstimatedNumSplits(Datastore datastore, Query query, @Nullable String namespace) {
      int numSplits;
      try {
        long estimatedSizeBytes = getEstimatedSizeBytes(datastore, query, namespace);
        logger.atInfo().log("Estimated size bytes for the query is: %s", estimatedSizeBytes);
        numSplits =
            (int)
                Math.min(
                    NUM_QUERY_SPLITS_MAX,
                    Math.round(((double) estimatedSizeBytes) / DEFAULT_BUNDLE_SIZE_BYTES));
      } catch (Exception e) {
        logger.atWarning().log("Failed the fetch estimatedSizeBytes for query: %s", query, e);
        // Fallback in case estimated size is unavailable.
        numSplits = NUM_QUERY_SPLITS_MIN;
      }
      return Math.max(numSplits, NUM_QUERY_SPLITS_MIN);
    }

    /**
     * Cloud Datastore system tables with statistics are periodically updated. This method fetches
     * the latest timestamp (in microseconds) of statistics update using the {@code __Stat_Total__}
     * table.
     */
    private static long queryLatestStatisticsTimestamp(
        Datastore datastore, @Nullable String namespace) throws DatastoreException {
      Query.Builder query = Query.newBuilder();
      // Note: namespace either being null or empty represents the default namespace, in which
      // case we treat it as not provided by the user.
      if (Strings.isNullOrEmpty(namespace)) {
        query.addKindBuilder().setName("__Stat_Total__");
      } else {
        query.addKindBuilder().setName("__Stat_Ns_Total__");
      }
      query.addOrder(makeOrder("timestamp", DESCENDING));
      query.setLimit(Int32Value.newBuilder().setValue(1));
      RunQueryRequest request = makeRequest(query.build(), namespace);

      RunQueryResponse response = datastore.runQuery(request);
      QueryResultBatch batch = response.getBatch();
      if (batch.getEntityResultsCount() == 0) {
        throw new NoSuchElementException("Datastore total statistics unavailable");
      }
      Entity entity = batch.getEntityResults(0).getEntity();
      return entity.getProperties().get("timestamp").getTimestampValue().getSeconds() * 1000000;
    }

    /** Retrieve latest table statistics for a given kind, namespace, and datastore. */
    private static Entity getLatestTableStats(
        String ourKind, @Nullable String namespace, Datastore datastore) throws DatastoreException {
      long latestTimestamp = queryLatestStatisticsTimestamp(datastore, namespace);
      logger.atInfo().log("Latest stats timestamp for kind %s is %s", ourKind, latestTimestamp);

      Query.Builder queryBuilder = Query.newBuilder();
      if (Strings.isNullOrEmpty(namespace)) {
        queryBuilder.addKindBuilder().setName("__Stat_Kind__");
      } else {
        queryBuilder.addKindBuilder().setName("__Stat_Ns_Kind__");
      }

      queryBuilder.setFilter(
          makeAndFilter(
              makeFilter("kind_name", EQUAL, makeValue(ourKind).build()).build(),
              makeFilter("timestamp", EQUAL, makeValue(latestTimestamp).build()).build()));

      RunQueryRequest request = makeRequest(queryBuilder.build(), namespace);

      long now = System.currentTimeMillis();
      RunQueryResponse response = datastore.runQuery(request);
      logger.atFine().log(
          "Query for per-kind statistics took %sms", System.currentTimeMillis() - now);

      QueryResultBatch batch = response.getBatch();
      if (batch.getEntityResultsCount() == 0) {
        throw new NoSuchElementException(
            "Datastore statistics for kind " + ourKind + " unavailable");
      }
      return batch.getEntityResults(0).getEntity();
    }

    /**
     * Get the estimated size of the data returned by the given query.
     *
     * <p>Cloud Datastore provides no way to get a good estimate of how large the result of a query
     * entity kind being queried, using the __Stat_Kind__ system table, assuming exactly 1 kind is
     * specified in the query.
     *
     * <p>See https://cloud.google.com/datastore/docs/concepts/stats.
     */
    static long getEstimatedSizeBytes(Datastore datastore, Query query, @Nullable String namespace)
        throws DatastoreException {
      String ourKind = query.getKind(0).getName();
      Entity entity = getLatestTableStats(ourKind, namespace, datastore);
      return entity.getProperties().get("entity_bytes").getIntegerValue();
    }

    private static PartitionId.Builder forNamespace(@Nullable String namespace) {
      PartitionId.Builder partitionBuilder = PartitionId.newBuilder();
      // Namespace either being null or empty represents the default namespace.
      // Datastore Client libraries expect users to not set the namespace proto field in
      // either of these cases.
      if (!Strings.isNullOrEmpty(namespace)) {
        partitionBuilder.setNamespaceId(namespace);
      }
      return partitionBuilder;
    }

    /** Builds a {@link RunQueryRequest} from the {@code query} and {@code namespace}. */
    static RunQueryRequest makeRequest(Query query, @Nullable String namespace) {
      return RunQueryRequest.newBuilder()
          .setQuery(query)
          .setPartitionId(forNamespace(namespace))
          .build();
    }

    /** Builds a {@link RunQueryRequest} from the {@code GqlQuery} and {@code namespace}. */
    private static RunQueryRequest makeRequest(GqlQuery gqlQuery, @Nullable String namespace) {
      return RunQueryRequest.newBuilder()
          .setGqlQuery(gqlQuery)
          .setPartitionId(forNamespace(namespace))
          .build();
    }

    /**
     * A helper function to get the split queries, taking into account the optional {@code
     * namespace}.
     */
    private static List<Query> splitQuery(
        Query query,
        @Nullable String namespace,
        Datastore datastore,
        QuerySplitter querySplitter,
        int numSplits)
        throws DatastoreException {
      // If namespace is set, include it in the split request so splits are calculated accordingly.
      return querySplitter.getSplits(query, forNamespace(namespace).build(), numSplits, datastore);
    }

    /**
     * Translates a Cloud Datastore gql query string to {@link Query}.
     *
     * <p>Currently, the only way to translate a gql query string to a Query is to run the query
     * against Cloud Datastore and extract the {@code Query} from the response. To prevent reading
     * any data, we set the {@code LIMIT} to 0 but if the gql query already has a limit set, we
     * catch the exception with {@code INVALID_ARGUMENT} error code and retry the translation
     * without the zero limit.
     *
     * <p>Note: This may result in reading actual data from Cloud Datastore but the service has a
     * cap on the number of entities returned for a single rpc request, so this should not be a
     * problem in practice.
     */
    private static Query translateGqlQueryWithLimitCheck(
        String gql, Datastore datastore, String namespace) throws DatastoreException {
      String gqlQueryWithZeroLimit = gql + " LIMIT 0";
      try {
        Query translatedQuery = translateGqlQuery(gqlQueryWithZeroLimit, datastore, namespace);
        // Clear the limit that we set.
        return translatedQuery.toBuilder().clearLimit().build();
      } catch (DatastoreException e) {
        // Note: There is no specific error code or message to detect if the query already has a
        // limit, so we just check for INVALID_ARGUMENT and assume that that the query might have
        // a limit already set.
        if (e.getCode() == Code.INVALID_ARGUMENT) {
          logger.atWarning().log(
              "Failed to translate Gql query '%s': %s", gqlQueryWithZeroLimit, e.getMessage());
          logger.atWarning().log(
              "User query might have a limit already set, so trying without zero limit");
          // Retry without the zero limit.
          return translateGqlQuery(gql, datastore, namespace);
        } else {
          throw e;
        }
      }
    }

    /** Translates a gql query string to {@link Query}. */
    private static Query translateGqlQuery(String gql, Datastore datastore, String namespace)
        throws DatastoreException {
      logger.atInfo().log("Translating gql %s", gql);
      GqlQuery gqlQuery = GqlQuery.newBuilder().setQueryString(gql).setAllowLiterals(true).build();
      RunQueryRequest req = makeRequest(gqlQuery, namespace);
      return datastore.runQuery(req).getQuery();
    }

    /**
     * Returns a new {@link MultiRead} that reads from the Cloud Datastore for the specified
     * project.
     */
    public MultiRead withProjectId(String projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return toBuilder().setProjectId(projectId).build();
    }

    /** Returns a new {@link MultiRead} that reads from the given namespace. */
    public MultiRead withNamespace(String namespace) {
      return toBuilder().setNamespace(namespace).build();
    }

    /**
     * Returns a new {@link MultiRead} that reads by splitting the given {@code query} into {@code
     * numQuerySplits}.
     *
     * <p>The semantics for the query splitting is defined below:
     *
     * <ul>
     *   <li>Any value less than or equal to 0 will be ignored, and the number of splits will be
     *       chosen dynamically at runtime based on the query data size.
     *   <li>Any value greater than {@link MultiRead#NUM_QUERY_SPLITS_MAX} will be capped at {@code
     *       NUM_QUERY_SPLITS_MAX}.
     *   <li>If the {@code query} has a user limit set, then {@code numQuerySplits} will be ignored
     *       and no split will be performed.
     *   <li>Under certain cases Cloud Datastore is unable to split query to the requested number of
     *       splits. In such cases we just use whatever the Cloud Datastore returns.
     * </ul>
     */
    public MultiRead withNumQuerySplits(int numQuerySplits) {
      return toBuilder()
          .setNumQuerySplits(Math.min(Math.max(numQuerySplits, 0), NUM_QUERY_SPLITS_MAX))
          .build();
    }

    /**
     * Returns a new {@link MultiRead} that reads from a Datastore Emulator running at the given
     * localhost address.
     */
    public MultiRead withLocalhost(String localhost) {
      return toBuilder().setLocalhost(localhost).build();
    }

    /** Returns Number of entities available for reading. */
    public long getNumEntities(
        PipelineOptions options, String ourKind, @Nullable String namespace) {
      try {
        V1Options v1Options = V1Options.from(getProjectId(), getNamespace(), getLocalhost());
        V1DatastoreFactory datastoreFactory = new V1DatastoreFactory();
        Datastore datastore =
            datastoreFactory.getDatastore(
                options, v1Options.getProjectId(), v1Options.getLocalhost());

        Entity entity = getLatestTableStats(ourKind, namespace, datastore);
        return entity.getProperties().get("count").getIntegerValue();
      } catch (Exception e) {
        return -1;
      }
    }

    @Override
    public PCollection<Entity> expand(PCollection<String> gqlQueries) {
      checkArgument(getProjectId() != null, "projectId cannot be null");

      V1Options v1Options = V1Options.from(getProjectId(), getNamespace(), getLocalhost());

      /*
       * This composite transform involves the following steps:
       *   1. Apply a {@link ParDo} that translates each query in {@code gqlQueries} into a {@code
       *   query}.
       *
       *   2. A {@link ParDo} splits the resulting query into {@code numQuerySplits} and
       *   assign each split query a unique {@code Integer} as the key. The resulting output is
       *   of the type {@code PCollection<KV<Integer, Query>>}.
       *
       *   If the value of {@code numQuerySplits} is less than or equal to 0, then the number of
       *   splits will be computed dynamically based on the size of the data for the {@code query}.
       *
       *   3. The resulting {@code PCollection} is sharded using a {@link GroupByKey} operation. The
       *   queries are extracted from they {@code KV<Integer, Iterable<Query>>} and flattened to
       *   output a {@code PCollection<Query>}.
       *
       *   4. In the third step, a {@code ParDo} reads entities for each query and outputs
       *   a {@code PCollection<Entity>}.
       */

      PCollection<Query> inputQuery =
          gqlQueries.apply(ParDo.of(new GqlQueryTranslateFn(v1Options)));

      return inputQuery
          .apply("Split", ParDo.of(new SplitQueryFn(v1Options, getNumQuerySplits())))
          .apply("Reshuffle", Reshuffle.viaRandomKey())
          .apply("Read", ParDo.of(new ReadFn(v1Options)));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder
          .addIfNotNull(DisplayData.item("projectId", getProjectId()).withLabel("ProjectId"))
          .addIfNotNull(DisplayData.item("namespace", getNamespace()).withLabel("Namespace"));
    }

    private static class V1Options implements HasDisplayData, Serializable {
      private final String project;
      private final @Nullable String namespace;
      private final @Nullable String localhost;

      private V1Options(String project, @Nullable String namespace, @Nullable String localhost) {
        this.project = project;
        this.namespace = namespace;
        this.localhost = localhost;
      }

      public static V1Options from(
          String projectId, @Nullable String namespace, @Nullable String localhost) {
        return new V1Options(projectId, namespace, localhost);
      }

      public String getProjectId() {
        return project;
      }

      public @Nullable String getNamespace() {
        return namespace;
      }

      public @Nullable String getLocalhost() {
        return localhost;
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        builder
            .addIfNotNull(DisplayData.item("projectId", getProjectId()).withLabel("ProjectId"))
            .addIfNotNull(DisplayData.item("namespace", getNamespace()).withLabel("Namespace"));
      }
    }

    /** A DoFn that translates a Cloud Datastore gql query string to {@code Query}. */
    static class GqlQueryTranslateFn extends DoFn<String, Query> {
      private final V1Options v1Options;
      private transient Datastore datastore;
      private final V1DatastoreFactory datastoreFactory;

      GqlQueryTranslateFn(V1Options options) {
        this(options, new V1DatastoreFactory());
      }

      GqlQueryTranslateFn(V1Options options, V1DatastoreFactory datastoreFactory) {
        this.v1Options = options;
        this.datastoreFactory = datastoreFactory;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(), v1Options.getProjectId(), v1Options.getLocalhost());
      }

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        String gqlQuery = c.element();
        logger.atInfo().log("User query: '%s'", gqlQuery);
        Query query =
            translateGqlQueryWithLimitCheck(gqlQuery, datastore, v1Options.getNamespace());
        logger.atInfo().log("User gql query translated to Query(%s)", query);
        c.output(query);
      }
    }

    /**
     * A {@link DoFn} that splits a given query into multiple sub-queries, assigns them unique keys
     * and outputs them as {@link KV}.
     */
    private static class SplitQueryFn extends DoFn<Query, Query> {
      private final V1Options options;
      // number of splits to make for a given query
      private final int numSplits;

      private final V1DatastoreFactory datastoreFactory;
      // Datastore client
      private transient Datastore datastore;
      // Query splitter
      private transient QuerySplitter querySplitter;

      public SplitQueryFn(V1Options options, int numSplits) {
        this(options, numSplits, new V1DatastoreFactory());
      }

      private SplitQueryFn(V1Options options, int numSplits, V1DatastoreFactory datastoreFactory) {
        this.options = options;
        this.numSplits = numSplits;
        this.datastoreFactory = datastoreFactory;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(), options.getProjectId(), options.getLocalhost());
        querySplitter = datastoreFactory.getQuerySplitter();
      }

      @ProcessElement
      public void processElement(ProcessContext c) {
        Query query = c.element();

        // If query has a user set limit, then do not split.
        if (query.hasLimit()) {
          c.output(query);
          return;
        }

        int estimatedNumSplits;
        // Compute the estimated numSplits if numSplits is not specified by the user.
        if (numSplits <= 0) {
          estimatedNumSplits = getEstimatedNumSplits(datastore, query, options.getNamespace());
        } else {
          estimatedNumSplits = numSplits;
        }

        logger.atInfo().log("Splitting the query into %s splits", estimatedNumSplits);
        List<Query> querySplits;
        try {
          querySplits =
              splitQuery(
                  query, options.getNamespace(), datastore, querySplitter, estimatedNumSplits);
        } catch (Exception e) {
          logger.atWarning().log("Unable to parallelize the given query: %s", query, e);
          querySplits = ImmutableList.of(query);
        }

        // assign unique keys to query splits.
        for (Query subquery : querySplits) {
          c.output(subquery);
        }
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        super.populateDisplayData(builder);
        builder.include("options", options);
        if (numSplits > 0) {
          builder.add(
              DisplayData.item("numQuerySplits", numSplits)
                  .withLabel("Requested number of Query splits"));
        }
      }
    }

    /** A {@link DoFn} that reads entities from Cloud Datastore for each query. */
    private static class ReadFn extends DoFn<Query, Entity> {
      private final V1Options options;
      private final V1DatastoreFactory datastoreFactory;
      // Datastore client
      private transient Datastore datastore;
      private final Counter rpcErrors = Metrics.counter(ReadFn.class, "datastoreRpcErrors");
      private final Counter rpcSuccesses = Metrics.counter(ReadFn.class, "datastoreRpcSuccesses");
      private static final int MAX_RETRIES = 5;
      private static final FluentBackoff RUNQUERY_BACKOFF =
          FluentBackoff.DEFAULT
              .withMaxRetries(MAX_RETRIES)
              .withInitialBackoff(Duration.standardSeconds(5));

      public ReadFn(V1Options options) {
        this(options, new V1DatastoreFactory());
      }

      private ReadFn(V1Options options, V1DatastoreFactory datastoreFactory) {
        this.options = options;
        this.datastoreFactory = datastoreFactory;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(), options.getProjectId(), options.getLocalhost());
      }

      private RunQueryResponse runQueryWithRetries(RunQueryRequest request) throws Exception {
        Sleeper sleeper = Sleeper.DEFAULT;
        BackOff backoff = RUNQUERY_BACKOFF.backoff();
        while (true) {
          try {
            RunQueryResponse response = datastore.runQuery(request);
            rpcSuccesses.inc();
            return response;
          } catch (DatastoreException exception) {
            rpcErrors.inc();

            if (NON_RETRYABLE_ERRORS.contains(exception.getCode())) {
              throw exception;
            }
            if (!BackOffUtils.next(sleeper, backoff)) {
              logger.atSevere().log("Aborting after %s retries.", MAX_RETRIES);
              throw exception;
            }
          }
        }
      }

      /** Read and output entities for the given query. */
      @ProcessElement
      public void processElement(ProcessContext context) throws Exception {
        Query query = context.element();
        String namespace = options.getNamespace();
        int userLimit = query.hasLimit() ? query.getLimit().getValue() : Integer.MAX_VALUE;

        boolean moreResults = true;
        QueryResultBatch currentBatch = null;

        while (moreResults) {
          Query.Builder queryBuilder = query.toBuilder();
          queryBuilder.setLimit(
              Int32Value.newBuilder().setValue(Math.min(userLimit, QUERY_BATCH_LIMIT)));

          if (currentBatch != null && !currentBatch.getEndCursor().isEmpty()) {
            queryBuilder.setStartCursor(currentBatch.getEndCursor());
          }

          RunQueryRequest request = makeRequest(queryBuilder.build(), namespace);
          RunQueryResponse response = runQueryWithRetries(request);

          currentBatch = response.getBatch();

          // MORE_RESULTS_AFTER_LIMIT is not implemented yet:
          // https://groups.google.com/forum/#!topic/gcd-discuss/iNs6M1jA2Vw, so
          // use result count to determine if more results might exist.
          int numFetch = currentBatch.getEntityResultsCount();
          if (query.hasLimit()) {
            verify(
                userLimit >= numFetch,
                "Expected userLimit %s >= numFetch %s, because query limit %s must be <= userLimit",
                userLimit,
                numFetch,
                query.getLimit());
            userLimit -= numFetch;
          }

          // output all the entities from the current batch.
          for (EntityResult entityResult : currentBatch.getEntityResultsList()) {
            context.output(entityResult.getEntity());
          }

          // Check if we have more entities to be read.
          moreResults =
              // User-limit does not exist (so userLimit == MAX_VALUE) and/or has not been satisfied
              (userLimit > 0)
                  // All indications from the API are that there are/may be more results.
                  && ((numFetch == QUERY_BATCH_LIMIT)
                      || (currentBatch.getMoreResults() == NOT_FINISHED));
        }
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        super.populateDisplayData(builder);
        builder.include("options", options);
      }
    }
  }

  /**
   * A wrapper factory class for Cloud Datastore singleton classes {@link DatastoreFactory} and
   * {@link QuerySplitter}
   *
   * <p>{@link DatastoreFactory} and {@link QuerySplitter} are not java serializable, hence wrapping
   * them under this class, which implements {@link Serializable}.
   */
  private static class V1DatastoreFactory implements Serializable {

    /** Builds a Cloud Datastore client for the given pipeline options and project. */
    public Datastore getDatastore(PipelineOptions pipelineOptions, String projectId) {
      return getDatastore(pipelineOptions, projectId, null);
    }

    /**
     * Builds a Cloud Datastore client for the given pipeline options, project and an optional
     * locahost.
     */
    public Datastore getDatastore(
        PipelineOptions pipelineOptions, String projectId, @Nullable String localhost) {
      Credentials credential = pipelineOptions.as(GcpOptions.class).getGcpCredential();
      HttpRequestInitializer initializer;
      if (credential != null) {
        initializer =
            new ChainingHttpRequestInitializer(
                new HttpCredentialsAdapter(credential), new RetryHttpRequestInitializer());
      } else {
        initializer = new RetryHttpRequestInitializer();
      }

      DatastoreOptions.Builder builder =
          new DatastoreOptions.Builder().projectId(projectId).initializer(initializer);

      if (localhost != null) {
        builder.localHost(localhost);
      } else {
        builder.host("batch-datastore.googleapis.com");
      }

      return DatastoreFactory.get().create(builder.build());
    }

    /** Builds a Cloud Datastore {@link QuerySplitter}. */
    public QuerySplitter getQuerySplitter() {
      return DatastoreHelper.getQuerySplitter();
    }
  }
}
