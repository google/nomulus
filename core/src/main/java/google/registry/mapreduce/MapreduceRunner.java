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

package google.registry.mapreduce;

import static com.google.appengine.tools.pipeline.PipelineServiceFactory.newPipelineService;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.MapJob;
import com.google.appengine.tools.mapreduce.MapReduceJob;
import com.google.appengine.tools.mapreduce.MapReduceSettings;
import com.google.appengine.tools.mapreduce.MapReduceSpecification;
import com.google.appengine.tools.mapreduce.MapSettings;
import com.google.appengine.tools.mapreduce.MapSpecification;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Marshallers;
import com.google.appengine.tools.mapreduce.Output;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.outputs.NoOutput;
import com.google.appengine.tools.pipeline.Job0;
import com.google.appengine.tools.pipeline.JobSetting;
import com.google.common.flogger.FluentLogger;
import google.registry.mapreduce.inputs.ConcatenatingInput;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.AppEngineServiceUtils;
import java.io.Serializable;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * Runner for map-only or full map and reduce mapreduces.
 *
 * <p>We use hardcoded serialization marshallers for moving data between steps, so all types used as
 * keys or values must implement {@link Serializable}.
 */
public class MapreduceRunner {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PARAM_DRY_RUN = "dryRun";
  public static final String PARAM_MAP_SHARDS = "mapShards";
  public static final String PARAM_REDUCE_SHARDS = "reduceShards";
  public static final String PARAM_FAST = "fast";

  private static final String BASE_URL = "/_dr/mapreduce/";
  private static final String QUEUE_NAME = "mapreduce";

  private static final String MAPREDUCE_CONSOLE_LINK_FORMAT =
      "Mapreduce console: https://%s/_ah/pipeline/status.html?root=%s";

  private final Optional<Integer> httpParamMapShards;
  private final Optional<Integer> httpParamReduceShards;
  private final AppEngineServiceUtils appEngineServiceUtils;

  // Default to 3 minutes since many slices will contain Datastore queries that time out at 4:30.
  private Duration sliceDuration = Duration.standardMinutes(3);
  private String jobName;
  private String moduleName;

  // Defaults for number of mappers/reducers if not specified in HTTP params.  The max allowable
  // count for both (which is specified in the App Engine mapreduce framework) is 1000.  We use 100
  // mapper shards because there's a bottleneck in the App Engine mapreduce framework caused by
  // updating the mapreduce status on a single Datastore entity (which only supports so many writes
  // per second).  The existing mapreduces don't actually do that much work for TLDs that aren't
  // .com-sized, so the shards finish so quickly that contention becomes a problem.  This number can
  // always be tuned up for large registry systems with on the order of hundreds of thousands of
  // entities on up.
  // The default reducer shard count is one because most mapreduces use it to collate and output
  // results.  The ones that actually perform a substantial amount of work in a reduce step use a
  // higher non-default number of reducer shards.
  private int defaultMapShards = 100;
  private int defaultReduceShards = 1;

  /**
   * @param mapShards number of map shards; if omitted, the {@link Input} objects will choose
   * @param reduceShards number of reduce shards; if omitted, uses {@link #defaultReduceShards}
   */
  @Inject
  public MapreduceRunner(
      @Parameter(PARAM_MAP_SHARDS) Optional<Integer> mapShards,
      @Parameter(PARAM_REDUCE_SHARDS) Optional<Integer> reduceShards,
      AppEngineServiceUtils appEngineServiceUtils) {
    this.httpParamMapShards = mapShards;
    this.httpParamReduceShards = reduceShards;
    this.appEngineServiceUtils = appEngineServiceUtils;
  }

  /** Set the max time to run a slice before serializing; defaults to 3 minutes. */
  public MapreduceRunner setSliceDuration(Duration sliceDuration) {
    this.sliceDuration = checkArgumentNotNull(sliceDuration, "sliceDuration");
    return this;
  }

  /** Set the human readable job name for display purposes. */
  public MapreduceRunner setJobName(String jobName) {
    this.jobName = checkArgumentNotNull(jobName, "jobName");
    return this;
  }

  /** Set the module to run in. */
  public MapreduceRunner setModuleName(String moduleName) {
    this.moduleName = checkArgumentNotNull(moduleName, "moduleName");
    return this;
  }

  /** Set the default number of mappers, if not overridden by the http param. */
  public MapreduceRunner setDefaultMapShards(int defaultMapShards) {
    this.defaultMapShards = defaultMapShards;
    return this;
  }

  /** Set the default number of reducers, if not overridden by the http param. */
  public MapreduceRunner setDefaultReduceShards(int defaultReduceShards) {
    this.defaultReduceShards = defaultReduceShards;
    return this;
  }

  /**
   * Create a map-only mapreduce to be run as part of a pipeline.
   *
   * @see #runMapOnly for creating and running an independent map-only mapreduce
   *
   * @param mapper instance of a mapper class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <O> individual output record type sent to the {@link Output}
   * @param <R> overall output result type
   */
  public <I, O, R> MapJob<I, O, R> createMapOnlyJob(
      Mapper<I, Void, O> mapper,
      Output<O, R> output,
      Iterable<? extends Input<? extends I>> inputs) {
    checkCommonRequiredFields(inputs, mapper);
    return new MapJob<>(
        new MapSpecification.Builder<I, O, R>()
            .setJobName(jobName)
            .setInput(new ConcatenatingInput<>(inputs, httpParamMapShards.orElse(defaultMapShards)))
            .setMapper(mapper)
            .setOutput(output)
            .build(),
        new MapSettings.Builder()
            .setWorkerQueueName(QUEUE_NAME)
            .setBaseUrl(BASE_URL)
            .setModule(moduleName)
            .setMillisPerSlice((int) sliceDuration.getMillis())
            .build());
  }

  /**
   * Kick off a map-only mapreduce.
   *
   * <p>For simplicity, the mapreduce is hard-coded with {@link NoOutput}, on the assumption that
   * all work will be accomplished via side effects during the map phase.
   *
   * @see #createMapOnlyJob for creating and running a map-only mapreduce as part of a pipeline
   * @param mapper instance of a mapper class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @return the job id
   */
  public <I> MapreduceRunnerResult runMapOnly(
      Mapper<I, Void, Void> mapper, Iterable<? extends Input<? extends I>> inputs) {
    return runAsPipeline(createMapOnlyJob(mapper, new NoOutput<Void, Void>(), inputs));
  }

  /**
   * Create a mapreduce job to be run as part of a pipeline.
   *
   * @see #runMapreduce for creating and running an independent mapreduce
   *
   * @param mapper instance of a mapper class
   * @param reducer instance of a reducer class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <K> emitted key type
   * @param <V> emitted value type
   * @param <O> individual output record type sent to the {@link Output}
   * @param <R> overall output result type
   */
  public final <I, K extends Serializable, V extends Serializable, O, R> MapReduceJob<I, K, V, O, R>
      createMapreduceJob(
          Mapper<I, K, V> mapper,
          Reducer<K, V, O> reducer,
          Iterable<? extends Input<? extends I>> inputs,
          Output<O, R> output) {
    checkCommonRequiredFields(inputs, mapper);
    checkArgumentNotNull(reducer, "reducer");
    return new MapReduceJob<>(
        new MapReduceSpecification.Builder<I, K, V, O, R>()
            .setJobName(jobName)
            .setInput(new ConcatenatingInput<>(inputs, httpParamMapShards.orElse(defaultMapShards)))
            .setMapper(mapper)
            .setReducer(reducer)
            .setOutput(output)
            .setKeyMarshaller(Marshallers.getSerializationMarshaller())
            .setValueMarshaller(Marshallers.getSerializationMarshaller())
            .setNumReducers(httpParamReduceShards.orElse(defaultReduceShards))
            .build(),
        new MapReduceSettings.Builder()
            .setWorkerQueueName(QUEUE_NAME)
            .setBaseUrl(BASE_URL)
            .setModule(moduleName)
            .setMillisPerSlice((int) sliceDuration.getMillis())
            .build());
  }

  /**
   * Kick off a mapreduce job.
   *
   * <p>For simplicity, the mapreduce is hard-coded with {@link NoOutput}, on the assumption that
   * all work will be accomplished via side effects during the map or reduce phases.
   *
   * @see #createMapreduceJob for creating and running a mapreduce as part of a pipeline
   * @param mapper instance of a mapper class
   * @param reducer instance of a reducer class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <K> emitted key type
   * @param <V> emitted value type
   * @return the job id
   */
  public final <I, K extends Serializable, V extends Serializable>
      MapreduceRunnerResult runMapreduce(
          Mapper<I, K, V> mapper,
          Reducer<K, V, Void> reducer,
          Iterable<? extends Input<? extends I>> inputs) {
    return runMapreduce(mapper, reducer, inputs, new NoOutput<Void, Void>());
  }

  /**
   * Kick off a mapreduce job with specified Output handler.
   *
   * @see #createMapreduceJob for creating and running a mapreduce as part of a pipeline
   * @param mapper instance of a mapper class
   * @param reducer instance of a reducer class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <K> emitted key type
   * @param <V> emitted value type
   * @param <O> emitted output type
   * @param <R> return value of output
   * @return the job id
   */
  public final <I, K extends Serializable, V extends Serializable, O, R>
      MapreduceRunnerResult runMapreduce(
          Mapper<I, K, V> mapper,
          Reducer<K, V, O> reducer,
          Iterable<? extends Input<? extends I>> inputs,
          Output<O, R> output) {
    return runAsPipeline(createMapreduceJob(mapper, reducer, inputs, output));
  }

  private void checkCommonRequiredFields(Iterable<?> inputs, Mapper<?, ?, ?> mapper) {
    checkNotNull(jobName, "jobName");
    checkNotNull(moduleName, "moduleName");
    checkArgumentNotNull(inputs, "inputs");
    checkArgumentNotNull(mapper, "mapper");
  }

  private MapreduceRunnerResult runAsPipeline(Job0<?> job) {
    String jobId =
        newPipelineService()
            .startNewPipeline(
                job, new JobSetting.OnModule(moduleName), new JobSetting.OnQueue(QUEUE_NAME));
    logger.atInfo().log(
        "Started '%s' %s job: %s",
        jobName, job instanceof MapJob ? "map" : "mapreduce", renderMapreduceConsoleLink(jobId));
    return new MapreduceRunnerResult(jobId);
  }

  private String renderMapreduceConsoleLink(String jobId) {
    return String.format(
        MAPREDUCE_CONSOLE_LINK_FORMAT,
        appEngineServiceUtils.convertToSingleSubdomain(
            appEngineServiceUtils.getServiceHostname("backend")),
        jobId);
  }

  /**
   * Class representing the result of kicking off a mapreduce.
   *
   * <p>This is used to send a link to the mapreduce console.
   */
  public class MapreduceRunnerResult {

    private final String jobId;

    private MapreduceRunnerResult(String jobId) {
      this.jobId = jobId;
    }

    public void sendLinkToMapreduceConsole(Response response) {
      response.setPayload(getLinkToMapreduceConsole() + "\n");
    }

    public String getLinkToMapreduceConsole() {
      return renderMapreduceConsoleLink(jobId);
    }
  }
}
