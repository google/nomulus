// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bigquery;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static google.registry.bigquery.BigqueryUtils.toJobReferenceString;
import static google.registry.config.RegistryConfig.getProjectId;
import static org.joda.time.DateTimeZone.UTC;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.DatasetReference;
import com.google.api.services.bigquery.model.ErrorProto;
import com.google.api.services.bigquery.model.GetQueryResultsResponse;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationExtract;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobConfigurationQuery;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.JobStatistics;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableCell;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.ViewDefinition;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import google.registry.bigquery.BigqueryUtils.DestinationFormat;
import google.registry.bigquery.BigqueryUtils.SourceFormat;
import google.registry.bigquery.BigqueryUtils.TableType;
import google.registry.bigquery.BigqueryUtils.WriteDisposition;
import google.registry.util.NonFinalForTesting;
import google.registry.util.Sleeper;
import google.registry.util.SqlTemplate;
import google.registry.util.SystemSleeper;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Class encapsulating parameters and state for accessing the Bigquery API. */
public class BigqueryConnection implements AutoCloseable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Duration MIN_POLL_INTERVAL = Duration.millis(500);

  @NonFinalForTesting
  private static Sleeper sleeper = new SystemSleeper();

  /** Default name of the default dataset to use for requests to the API. */
  public static final String DEFAULT_DATASET_NAME = "testing";

  /** Default dataset to use for storing temporary tables. */
  private static final String TEMP_DATASET_NAME = "__temp__";

  /** Default time to live for temporary tables. */
  private static final Duration TEMP_TABLE_TTL = Duration.standardHours(24);

  /** Bigquery client instance wrapped by this class. */
  private final Bigquery bigquery;

  /** Executor service for bigquery jobs. */
  private ListeningExecutorService service;

  /** Pseudo-randomness source to use for creating random table names. */
  private Random random = new Random();

  /** Name of the default dataset to use for inserting tables. */
  private String datasetId = DEFAULT_DATASET_NAME;

  /** Whether to automatically overwrite existing tables and views. */
  private boolean overwrite = false;

  /** Duration to wait between polls for job status. */
  private Duration pollInterval = Duration.millis(1000);

  BigqueryConnection(Bigquery bigquery) {
    this.bigquery = bigquery;
  }

  /** Builder for a {@link BigqueryConnection}, since the latter is immutable once created. */
  public static class Builder {
    private BigqueryConnection instance;

    @Inject
    Builder(Bigquery bigquery) {
      instance = new BigqueryConnection(bigquery);
    }

    /**
     * The BigqueryConnection takes ownership of this {@link ExecutorService} and will
     * shut it down when the BigqueryConnection is closed.
     */
    public Builder setExecutorService(ExecutorService executorService) {
      instance.service = MoreExecutors.listeningDecorator(executorService);
      return this;
    }

    public Builder setDatasetId(String datasetId) {
      instance.datasetId = checkNotNull(datasetId);
      return this;
    }

    public Builder setOverwrite(boolean overwrite) {
      instance.overwrite = overwrite;
      return this;
    }

    public Builder setPollInterval(Duration pollInterval) {
      checkArgument(
          !pollInterval.isShorterThan(MIN_POLL_INTERVAL),
          "poll interval must be at least %ldms", MIN_POLL_INTERVAL.getMillis());
      instance.pollInterval = pollInterval;
      return this;
    }

    public BigqueryConnection build() {
      try {
        checkNotNull(instance.service, "Must provide executor service");
        instance.initialize();
        return instance;
      } catch (Throwable e) {
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException("Cannot initialize BigqueryConnection", e);
      } finally {
        // Clear the internal instance so you can't accidentally mutate it through this builder.
        instance = null;
      }
    }
  }

  /**
   * Class that wraps a normal Bigquery API Table object to make it immutable from the client side
   * and give it additional semantics as a "destination" for load or query jobs, with an overwrite
   * flag set by the client upon creation.
   *
   * <p>Additionally provides encapsulation so that clients of BigqueryConnection don't need to take
   * any direct dependencies on Bigquery API classes and can instead use DestinationTable.
   */
  public static class DestinationTable {
    /** The wrapped Bigquery API Table object. */
    private final Table table;

    /** The type of this table. */
    private final TableType type;

    /** The write disposition for jobs writing to this destination table. */
    private final WriteDisposition writeDisposition;

    /**
     * A query to package with this table if the type is VIEW; not immutable but also not visible
     * to clients.
     */
    private String query;

    /** A builder for DestinationTable. */
    public static final class Builder {
      private final Table table = new Table();
      private final TableReference tableRef = new TableReference();
      private TableType type = TableType.TABLE;
      private WriteDisposition writeDisposition = WriteDisposition.WRITE_EMPTY;

      public Builder datasetId(String datasetId) {
        tableRef.setDatasetId(datasetId);
        return this;
      }

      public Builder name(String name) {
        tableRef.setTableId(name);
        return this;
      }

      public Builder description(String description) {
        table.setDescription(description);
        return this;
      }

      public Builder type(TableType type) {
        this.type = type;
        return this;
      }

      public Builder timeToLive(Duration duration) {
        this.table.setExpirationTime(new DateTime(UTC).plus(duration).getMillis());
        return this;
      }

      public Builder overwrite(boolean overwrite) {
        if (overwrite) {
          this.writeDisposition = WriteDisposition.WRITE_TRUNCATE;
        }
        return this;
      }

      public Builder append(boolean append) {
        if (append) {
          this.writeDisposition = WriteDisposition.WRITE_APPEND;
        }
        return this;
      }

      public DestinationTable build() {
        tableRef.setProjectId(getProjectId());
        table.setTableReference(tableRef);
        checkState(!isNullOrEmpty(table.getTableReference().getDatasetId()));
        checkState(!isNullOrEmpty(table.getTableReference().getTableId()));
        return new DestinationTable(this);
      }
    }

    /** Constructs a new DestinationTable from its Builder. */
    private DestinationTable(Builder b) {
      table = b.table.clone();
      type = b.type;
      writeDisposition = b.writeDisposition;
    }

    /**
     * Stores the provided query with this DestinationTable and returns it; used for packaging
     * a query along with the DestinationTable before sending it to the table update logic.
     */
    private DestinationTable withQuery(String query) {
      checkState(type == TableType.VIEW);
      this.query = query;
      return this;
    }

    /** Returns a new copy of the Bigquery API Table object wrapped by this DestinationTable. */
    private Table getTable() {
      Table tableCopy = table.clone();
      if (type == TableType.VIEW) {
        tableCopy.setView(new ViewDefinition().setQuery(query));
      }
      return tableCopy;
    }

    /** Returns the write disposition that should be used for jobs writing to this table. */
    private WriteDisposition getWriteDisposition() {
      return writeDisposition;
    }

    /** Returns a new copy of the TableReference for the Table wrapped by this DestinationTable. */
    private TableReference getTableReference() {
      return table.getTableReference().clone();
    }

    /** Returns a string representation of the TableReference for the wrapped table. */
    public String getStringReference() {
      return tableReferenceToString(table.getTableReference());
    }

    /** Returns a string representation of the given TableReference. */
    private static String tableReferenceToString(TableReference tableRef) {
      return String.format(
          "%s:%s.%s",
          tableRef.getProjectId(),
          tableRef.getDatasetId(),
          tableRef.getTableId());
    }
  }

  /**
   * Initializes the BigqueryConnection object by setting up the API client and creating the default
   * dataset if it doesn't exist.
   */
  private void initialize() throws Exception {
    createDatasetIfNeeded(datasetId);
    createDatasetIfNeeded(TEMP_DATASET_NAME);
  }

  /**
   * Closes the BigqueryConnection object by shutting down the executor service.  Clients
   * should only call this after all ListenableFutures obtained from BigqueryConnection methods
   * have resolved; this method does not block on their completion.
   */
  @Override
  public void close() {
    service.shutdown();
  }

  /** Returns a partially built DestinationTable with the default dataset and overwrite behavior. */
  public DestinationTable.Builder buildDestinationTable(String tableName) {
    return new DestinationTable.Builder()
        .datasetId(datasetId)
        .type(TableType.TABLE)
        .name(tableName)
        .overwrite(overwrite);
  }

  /**
   * Returns a partially built DestinationTable with a randomly generated name under the default
   * temporary table dataset, with the default TTL and overwrite behavior.
   */
  public DestinationTable.Builder buildTemporaryTable() {
    return new DestinationTable.Builder()
        .datasetId(TEMP_DATASET_NAME)
        .type(TableType.TABLE)
        .name(getRandomTableName())
        .timeToLive(TEMP_TABLE_TTL)
        .overwrite(overwrite);
  }

  /** Returns a random table name consisting only of the chars {@code [a-v0-9_]}. */
  private String getRandomTableName() {
    byte[] randBytes = new byte[8];  // 64 bits of randomness ought to be plenty.
    random.nextBytes(randBytes);
    return "_" + BaseEncoding.base32Hex().lowerCase().omitPadding().encode(randBytes);
  }

  /**
   * Updates the specified Bigquery table to reflect the metadata from the input.
   *
   * <p>Returns the input DestinationTable. If the specified table does not already exist, it will
   * be inserted into the dataset.
   *
   * <p>Clients can call this function directly to update a table on demand, or can pass it to
   * Futures.transform() to update a table produced as the asynchronous result of a load or query
   * job (e.g. to add a description to it).
   */
  private DestinationTable updateTable(final DestinationTable destinationTable) {
    Table table = destinationTable.getTable();
    TableReference ref = table.getTableReference();
    try {
      if (checkTableExists(ref.getDatasetId(), ref.getTableId())) {
        // Make sure to use patch() rather than update(). The former changes only those properties
        // which are specified, while the latter would change everything, blanking out unspecified
        // properties.
        bigquery
            .tables()
            .patch(ref.getProjectId(), ref.getDatasetId(), ref.getTableId(), table)
            .execute();
      } else {
        bigquery.tables().insert(ref.getProjectId(), ref.getDatasetId(), table).execute();
      }
      return destinationTable;
    } catch (IOException e) {
      throw BigqueryJobFailureException.create(e);
    }
  }

  /**
   * Starts an asynchronous load job to populate the specified destination table with the given
   * source URIs and source format. Returns a ListenableFuture that holds the same destination table
   * object on success.
   */
  public ListenableFuture<DestinationTable> startLoad(
      DestinationTable dest, SourceFormat sourceFormat, Iterable<String> sourceUris) {
    Job job = new Job()
        .setConfiguration(new JobConfiguration()
            .setLoad(new JobConfigurationLoad()
                .setWriteDisposition(dest.getWriteDisposition().toString())
                .setSourceFormat(sourceFormat.toString())
                .setSourceUris(ImmutableList.copyOf(sourceUris))
                .setDestinationTable(dest.getTableReference())));
    return transform(runJobToCompletion(job, dest), this::updateTable, directExecutor());
  }

  /**
   * Starts an asynchronous query job to populate the specified destination table with the results
   * of the specified query, or if the table is a view, to update the view to reflect that query.
   * Returns a ListenableFuture that holds the same destination table object on success.
   */
  public ListenableFuture<DestinationTable> startQuery(String querySql, DestinationTable dest) {
    if (dest.type == TableType.VIEW) {
      // Use Futures.transform() rather than calling apply() directly so that any exceptions thrown
      // by calling updateTable will be propagated on the get() call, not from here.
      return transform(
          Futures.immediateFuture(dest.withQuery(querySql)), this::updateTable, directExecutor());
    } else {
      Job job = new Job()
          .setConfiguration(new JobConfiguration()
              .setQuery(new JobConfigurationQuery()
                  .setQuery(querySql)
                  .setDefaultDataset(getDataset())
                  .setWriteDisposition(dest.getWriteDisposition().toString())
                  .setDestinationTable(dest.getTableReference())));
      return transform(runJobToCompletion(job, dest), this::updateTable, directExecutor());
    }
  }

  /**
   * Starts an asynchronous query job to dump the results of the specified query into a local
   * ImmutableTable object, row-keyed by the row number (indexed from 1), column-keyed by the
   * TableFieldSchema for that column, and with the value object as the cell value.  Note that null
   * values will not actually be null, but they can be checked for using Data.isNull().
   *
   * <p>Returns a ListenableFuture that holds the ImmutableTable on success.
   */
  public ListenableFuture<ImmutableTable<Integer, TableFieldSchema, Object>>
      queryToLocalTable(String querySql) {
    Job job = new Job()
        .setConfiguration(new JobConfiguration()
            .setQuery(new JobConfigurationQuery()
                .setQuery(querySql)
                .setDefaultDataset(getDataset())));
    return transform(runJobToCompletion(job), this::getQueryResults, directExecutor());
  }

  /**
   * Returns the result of calling queryToLocalTable, but synchronously to avoid spawning new
   * background threads, which App Engine doesn't support.
   *
   * @see <a href="https://cloud.google.com/appengine/docs/standard/java/runtime#Threads">App Engine
   *     Runtime</a>
   */
  public ImmutableTable<Integer, TableFieldSchema, Object> queryToLocalTableSync(String querySql) {
    Job job = new Job()
        .setConfiguration(new JobConfiguration()
            .setQuery(new JobConfigurationQuery()
                .setQuery(querySql)
                .setDefaultDataset(getDataset())));
    return getQueryResults(runJob(job));
  }

  /**
   * Returns the query results for the given job as an ImmutableTable, row-keyed by row number
   * (indexed from 1), column-keyed by the TableFieldSchema for that field, and with the value
   * object as the cell value.  Note that null values will not actually be null (since we're using
   * ImmutableTable) but they can be checked for using Data.isNull().
   *
   * <p>This table is fully materialized in memory (not lazily loaded), so it should not be used
   * with queries expected to return large results.
   */
  private ImmutableTable<Integer, TableFieldSchema, Object> getQueryResults(Job job) {
    try {
      ImmutableTable.Builder<Integer, TableFieldSchema, Object> builder =
          new ImmutableTable.Builder<>();
      String pageToken = null;
      int rowNumber = 1;
      while (true) {
        GetQueryResultsResponse queryResults = bigquery.jobs()
              .getQueryResults(getProjectId(), job.getJobReference().getJobId())
              .setPageToken(pageToken)
              .execute();
        // If the job isn't complete yet, retry; getQueryResults() waits for up to 10 seconds on
        // each invocation so this will effectively poll for completion.
        if (queryResults.getJobComplete()) {
          List<TableFieldSchema> schemaFields = queryResults.getSchema().getFields();
          for (TableRow row : queryResults.getRows()) {
            Iterator<TableFieldSchema> fieldIterator = schemaFields.iterator();
            Iterator<TableCell> cellIterator = row.getF().iterator();
            while (fieldIterator.hasNext() && cellIterator.hasNext()) {
              builder.put(rowNumber, fieldIterator.next(), cellIterator.next().getV());
            }
            rowNumber++;
          }
          pageToken = queryResults.getPageToken();
          if (pageToken == null) {
            break;
          }
        }
      }
      return builder.build();
    } catch (IOException e) {
      throw BigqueryJobFailureException.create(e);
    }
  }

  /**
   * Starts an asynchronous job to extract the specified source table and output it to the
   * given GCS filepath in the specified destination format, optionally printing headers.
   * Returns a ListenableFuture that holds the destination GCS URI on success.
   */
  private ListenableFuture<String> extractTable(
      DestinationTable sourceTable,
      String destinationUri,
      DestinationFormat destinationFormat,
      boolean printHeader) {
    checkArgument(sourceTable.type == TableType.TABLE);
    Job job = new Job()
        .setConfiguration(new JobConfiguration()
            .setExtract(new JobConfigurationExtract()
                .setSourceTable(sourceTable.getTableReference())
                .setDestinationFormat(destinationFormat.toString())
                .setDestinationUris(ImmutableList.of(destinationUri))
                .setPrintHeader(printHeader)));
    return runJobToCompletion(job, destinationUri);
  }

  /**
   * Starts an asynchronous job to extract the specified source table or view and output it to the
   * given GCS filepath in the specified destination format, optionally printing headers.
   * Returns a ListenableFuture that holds the destination GCS URI on success.
   */
  public ListenableFuture<String> extract(
      DestinationTable sourceTable,
      String destinationUri,
      DestinationFormat destinationFormat,
      boolean printHeader) {
    if (sourceTable.type == TableType.TABLE) {
      return extractTable(sourceTable, destinationUri, destinationFormat, printHeader);
    } else {
      // We can't extract directly from a view, so instead extract from a query dumping that view.
      return extractQuery(
          SqlTemplate
              .create("SELECT * FROM [%DATASET%.%TABLE%]")
              .put("DATASET", sourceTable.getTableReference().getDatasetId())
              .put("TABLE", sourceTable.getTableReference().getTableId())
              .build(),
          destinationUri,
          destinationFormat,
          printHeader);
    }
  }

  /**
   * Starts an asynchronous job to run the provided query, store the results in a temporary table,
   * and then extract the contents of that table to the given GCS filepath in the specified
   * destination format, optionally printing headers.
   *
   * <p>Returns a ListenableFuture that holds the destination GCS URI on success.
   */
  public ListenableFuture<String> extractQuery(
      String querySql,
      final String destinationUri,
      final DestinationFormat destinationFormat,
      final boolean printHeader) {
    // Note: although BigQuery queries save their results to an auto-generated anonymous table,
    // we can't rely on that for running the extract job because it may not be fully replicated.
    // Tracking bug for query-to-GCS support is b/13777340.
    DestinationTable tempTable = buildTemporaryTable().build();
    return transformAsync(
        startQuery(querySql, tempTable),
        tempTable1 -> extractTable(tempTable1, destinationUri, destinationFormat, printHeader),
        directExecutor());
  }

  /** @see #runJob(Job, AbstractInputStreamContent) */
  private Job runJob(Job job) {
    return runJob(job, null);
  }

  /** Launch a job, wait for it to complete, but <i>do not</i> check for errors. */
  private Job runJob(Job job, @Nullable AbstractInputStreamContent data) {
    return checkJob(waitForJob(launchJob(job, data)));
  }

  /**
   * Launch a job, but do not wait for it to complete.
   *
   * @throws BigqueryJobFailureException
   */
  private Job launchJob(Job job, @Nullable AbstractInputStreamContent data) {
    verify(job.getStatus() == null);
    try {
      return data != null
          ? bigquery.jobs().insert(getProjectId(), job, data).execute()
          : bigquery.jobs().insert(getProjectId(), job).execute();
    } catch (IOException e) {
      throw BigqueryJobFailureException.create(e);
    }
  }

  /**
   * Synchronously waits for a job to complete that's already been launched.
   *
   * @throws BigqueryJobFailureException
   */
  private Job waitForJob(Job job) {
    verify(job.getStatus() != null);
    while (!job.getStatus().getState().equals("DONE")) {
      sleeper.sleepUninterruptibly(pollInterval);
      JobReference ref = job.getJobReference();
      try {
        job = bigquery.jobs().get(ref.getProjectId(), ref.getJobId()).execute();
      } catch (IOException e) {
        throw BigqueryJobFailureException.create(e);
      }
    }
    return job;
  }

  /**
   * Checks completed job for errors.
   *
   * @throws BigqueryJobFailureException
   */
  private static Job checkJob(Job job) {
    verify(job.getStatus() != null);
    JobStatus jobStatus = job.getStatus();
    if (jobStatus.getErrorResult() != null) {
      throw BigqueryJobFailureException.create(jobStatus);
    } else {
      logger.atInfo().log(summarizeCompletedJob(job));
      if (jobStatus.getErrors() != null) {
        for (ErrorProto error : jobStatus.getErrors()) {
          logger.atWarning().log("%s: %s", error.getReason(), error.getMessage());
        }
      }
      return job;
    }
  }

  /** Returns a summarization of a completed job's statistics for logging. */
  private static String summarizeCompletedJob(Job job) {
    JobStatistics stats = job.getStatistics();
    return String.format(
        "Job took %,.3f seconds after a %,.3f second delay and processed %,d bytes (%s)",
        (stats.getEndTime() - stats.getStartTime()) / 1000.0,
        (stats.getStartTime() - stats.getCreationTime()) / 1000.0,
        stats.getTotalBytesProcessed(),
        toJobReferenceString(job.getJobReference()));
  }

  private <T> ListenableFuture<T> runJobToCompletion(Job job, T result) {
    return runJobToCompletion(job, result, null);
  }

  /** Runs job and returns a future that yields {@code result} when {@code job} is completed. */
  private <T> ListenableFuture<T> runJobToCompletion(
      final Job job,
      final T result,
      @Nullable final AbstractInputStreamContent data) {
    return service.submit(
        () -> {
          runJob(job, data);
          return result;
        });
  }

  private ListenableFuture<Job> runJobToCompletion(final Job job) {
    return service.submit(() -> runJob(job, null));
  }

  /** Helper that returns true if a dataset with this name exists. */
  public boolean checkDatasetExists(String datasetName) throws IOException {
    try {
      bigquery.datasets().get(getProjectId(), datasetName).execute();
      return true;
    } catch (GoogleJsonResponseException e) {
      if (e.getDetails() != null && e.getDetails().getCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  /** Helper that returns true if a table with this name and dataset name exists. */
  public boolean checkTableExists(String datasetName, String tableName) throws IOException {
    try {
      bigquery.tables().get(getProjectId(), datasetName, tableName).execute();
      return true;
    } catch (GoogleJsonResponseException e) {
      if (e.getDetails() != null && e.getDetails().getCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  /** Returns the dataset name that this bigquery connection uses by default. */
  public String getDatasetId() {
    return datasetId;
  }

  /** Returns dataset reference that can be used to avoid having to specify dataset in SQL code. */
  public DatasetReference getDataset() {
    return new DatasetReference()
        .setProjectId(getProjectId())
        .setDatasetId(getDatasetId());
  }

  /** Returns table reference with the projectId and datasetId filled out for you. */
  public TableReference getTable(String tableName) {
    return new TableReference()
        .setProjectId(getProjectId())
        .setDatasetId(getDatasetId())
        .setTableId(tableName);
  }

  /**
   * Helper that creates a dataset with this name if it doesn't already exist, and returns true
   * if creation took place.
   */
  public boolean createDatasetIfNeeded(String datasetName) throws IOException {
    if (!checkDatasetExists(datasetName)) {
      bigquery.datasets()
          .insert(getProjectId(), new Dataset().setDatasetReference(new DatasetReference()
              .setProjectId(getProjectId())
              .setDatasetId(datasetName)))
          .execute();
      logger.atInfo().log("Created dataset: %s:%s\n", getProjectId(), datasetName);
      return true;
    }
    return false;
  }

  /** Create a table from a SQL query if it doesn't already exist. */
  public TableReference ensureTable(TableReference table, String sqlQuery) {
    try {
      runJob(new Job()
          .setConfiguration(new JobConfiguration()
              .setQuery(new JobConfigurationQuery()
                  .setQuery(sqlQuery)
                  .setDefaultDataset(getDataset())
                  .setDestinationTable(table))));
    } catch (BigqueryJobFailureException e) {
      if (e.getReason().equals("duplicate")) {
        // Table already exists.
      } else {
        throw e;
      }
    }
    return table;
  }
}
