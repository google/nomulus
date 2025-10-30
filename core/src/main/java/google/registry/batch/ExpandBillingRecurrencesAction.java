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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.PARAM_DRY_RUN;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.billing.ExpandBillingRecurrencesPipeline;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.common.Cursor;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * An action that kicks off a {@link ExpandBillingRecurrencesPipeline} dataflow job to expand {@link
 * BillingRecurrence} billing events into synthetic {@link BillingEvent} events.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/expandBillingRecurrences",
    auth = Auth.AUTH_ADMIN)
public class ExpandBillingRecurrencesAction implements Runnable {

  public static final String PARAM_START_TIME = "startTime";
  public static final String PARAM_END_TIME = "endTime";
  public static final String PARAM_ADVANCE_CURSOR = "advanceCursor";

  private static final String PIPELINE_NAME = "expand_billing_recurrences_pipeline";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;

  @Inject
  @Parameter(PARAM_DRY_RUN)
  boolean isDryRun;

  @Inject
  @Parameter(PARAM_ADVANCE_CURSOR)
  boolean advanceCursor;

  @Inject
  @Parameter(PARAM_START_TIME)
  Optional<DateTime> startTimeParam;

  @Inject
  @Parameter(PARAM_END_TIME)
  Optional<DateTime> endTimeParam;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject
  @Config("defaultJobRegion")
  String jobRegion;

  @Inject
  @Config("beamStagingBucketUrl")
  String stagingBucketUrl;

  @Inject Dataflow dataflow;

  @Inject Response response;

  @Inject
  ExpandBillingRecurrencesAction() {}

  @Override
  public void run() {
    checkArgument(!(isDryRun && advanceCursor), "Cannot advance the cursor in a dry run.");
    DateTime endTime = endTimeParam.orElse(clock.nowUtc());
    checkArgument(
        !endTime.isAfter(clock.nowUtc()), "End time (%s) must be at or before now", endTime);
    DateTime startTime =
        startTimeParam.orElse(
            tm().transact(
                    () ->
                        tm().loadByKeyIfPresent(Cursor.createGlobalVKey(RECURRING_BILLING))
                            .orElse(Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                            .getCursorTime()));
    checkArgument(
        startTime.isBefore(endTime),
        String.format("Start time (%s) must be before end time (%s)", startTime, endTime));
    LaunchFlexTemplateParameter launchParameter =
        new LaunchFlexTemplateParameter()
            .setJobName(
                createJobName(
                    String.format(
                        "expand-billing-%s", startTime.toString("yyyy-MM-dd't'HH-mm-ss'z'")),
                    clock))
            .setContainerSpecGcsPath(
                String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
            .setParameters(
                new ImmutableMap.Builder<String, String>()
                    .put("registryEnvironment", RegistryEnvironment.get().name())
                    .put("startTime", startTime.toString("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
                    .put("endTime", endTime.toString("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
                    .put("isDryRun", Boolean.toString(isDryRun))
                    .put("advanceCursor", Boolean.toString(advanceCursor))
                    .build());
    logger.atInfo().log(
        "Launching billing recurrence expansion pipeline for event time range [%s, %s)%s.",
        startTime,
        endTime,
        isDryRun ? " in dry run mode" : advanceCursor ? "" : " without advancing the cursor");
    try {
      LaunchFlexTemplateResponse launchResponse =
          dataflow
              .projects()
              .locations()
              .flexTemplates()
              .launch(
                  projectId,
                  jobRegion,
                  new LaunchFlexTemplateRequest().setLaunchParameter(launchParameter))
              .execute();
      logger.atInfo().log("Got response: %s", launchResponse.getJob().toPrettyString());
      response.setStatus(SC_OK);
      response.setPayload(
          String.format(
              "Launched billing recurrence expansion pipeline: %s",
              launchResponse.getJob().getId()));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Pipeline Launch failed");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
    }
  }
}
