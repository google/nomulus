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

package google.registry.cron;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Multimaps.filterKeys;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.cron.CronModule.ENDPOINT_PARAM;
import static google.registry.cron.CronModule.EXCLUDE_PARAM;
import static google.registry.cron.CronModule.FOR_EACH_REAL_TLD_PARAM;
import static google.registry.cron.CronModule.FOR_EACH_TEST_TLD_PARAM;
import static google.registry.cron.CronModule.JITTER_SECONDS_PARAM;
import static google.registry.cron.CronModule.QUEUE_PARAM;
import static google.registry.cron.CronModule.RUN_IN_EMPTY_PARAM;
import static google.registry.model.tld.Registries.getTldsOfType;
import static google.registry.model.tld.Registry.TldType.REAL;
import static google.registry.model.tld.Registry.TldType.TEST;

import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.ParameterMap;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.CloudTasksUtils;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import javax.inject.Inject;

/**
 * Action for fanning out cron tasks shared by TLD.
 *
 * <h3>Parameters Reference</h3>
 *
 * <ul>
 *   <li>{@code endpoint} (Required) URL path of servlet to launch. This may contain pathargs.
 *   <li>{@code queue} (Required) Name of the App Engine push queue to which this task should be
 *       sent.
 *   <li>{@code forEachRealTld} Launch the task in each real TLD namespace.
 *   <li>{@code forEachTestTld} Launch the task in each test TLD namespace.
 *   <li>{@code runInEmpty} Launch the task once, without the TLD argument.
 *   <li>{@code exclude} TLDs to exclude.
 *   <li>{@code jitterSeconds} Randomly delay each task by up to this many seconds.
 *   <li>Any other parameters specified will be passed through as POST parameters to the called
 *       task.
 * </ul>
 *
 * <h3>Patharg Reference</h3>
 *
 * <p>The following values may be specified inside the "endpoint" param.
 *
 * <ul>
 *   <li>{@code :tld} Substituted with an ASCII tld, if tld fanout is enabled. This patharg is
 *       mostly useful for aesthetic purposes, since tasks are already namespaced.
 * </ul>
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/fanout",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class TldFanoutAction implements Runnable {

  /** A set of control params to TldFanoutAction that aren't passed down to the executing action. */
  private static final ImmutableSet<String> CONTROL_PARAMS =
      ImmutableSet.of(
          ENDPOINT_PARAM,
          QUEUE_PARAM,
          FOR_EACH_REAL_TLD_PARAM,
          FOR_EACH_TEST_TLD_PARAM,
          RUN_IN_EMPTY_PARAM,
          EXCLUDE_PARAM,
          JITTER_SECONDS_PARAM);

  private static final Random random = new Random();

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject CloudTasksUtils cloudTasksUtils;
  @Inject Response response;
  @Inject @Parameter(ENDPOINT_PARAM) String endpoint;
  @Inject @Parameter(QUEUE_PARAM) String queue;
  @Inject @Parameter(FOR_EACH_REAL_TLD_PARAM) boolean forEachRealTld;
  @Inject @Parameter(FOR_EACH_TEST_TLD_PARAM) boolean forEachTestTld;
  @Inject @Parameter(RUN_IN_EMPTY_PARAM) boolean runInEmpty;
  @Inject @Parameter(EXCLUDE_PARAM) ImmutableSet<String> excludes;
  @Inject @Parameter(JITTER_SECONDS_PARAM) Optional<Integer> jitterSeconds;
  @Inject @ParameterMap ImmutableListMultimap<String, String> params;

  @Inject
  TldFanoutAction() {}

  @Override
  public void run() {
    checkArgument(
        !(runInEmpty && (forEachTestTld || forEachRealTld)),
        "runInEmpty and forEach*Tld are mutually exclusive");
    checkArgument(
        runInEmpty || forEachTestTld || forEachRealTld,
        "At least one of runInEmpty, forEachTestTld, forEachRealTld must be given");
    checkArgument(
        !(runInEmpty && !excludes.isEmpty()), "Can't specify 'exclude' with 'runInEmpty'");
    ImmutableSet<String> tlds =
        runInEmpty
            ? ImmutableSet.of("")
            : Streams.concat(
                    forEachRealTld ? getTldsOfType(REAL).stream() : Stream.of(),
                    forEachTestTld ? getTldsOfType(TEST).stream() : Stream.of())
                .filter(not(in(excludes)))
                .collect(toImmutableSet());
    Multimap<String, String> flowThruParams = filterKeys(params, not(in(CONTROL_PARAMS)));
    StringBuilder outputPayload =
        new StringBuilder(
            String.format("OK: Launched the following %d tasks in queue %s\n", tlds.size(), queue));
    logger.atInfo().log("Launching %d tasks in queue %s.", tlds.size(), queue);
    if (tlds.isEmpty()) {
      logger.atWarning().log("No TLDs to fan-out!");
    }
    for (String tld : tlds) {
      Task task = createTask(tld, flowThruParams);
      Task createdTask = cloudTasksUtils.enqueue(queue, task);
      outputPayload.append(
          String.format(
              "- Task: '%s', tld: '%s', endpoint: '%s'\n",
              createdTask.getName(), tld, createdTask.getAppEngineHttpRequest().getRelativeUri()));
      logger.atInfo().log(
          "Task: '%s', tld: '%s', endpoint: '%s'.",
          createdTask.getName(), tld, createdTask.getAppEngineHttpRequest().getRelativeUri());
    }
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(outputPayload.toString());
  }

  private Task createTask(String tld, Multimap<String, String> params) {
    if (!tld.isEmpty()) {
      params = ArrayListMultimap.create(params);
      params.put(RequestParameters.PARAM_TLD, tld);
    }
    return CloudTasksUtils.createPostTask(
        endpoint, Service.BACKEND.toString(), params, clock, jitterSeconds);
  }
}
