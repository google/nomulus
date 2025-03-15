// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.request.RequestParameters.PARAM_DRY_RUN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.beam.wipeout.WipeOutContactHistoryPiiPipeline;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.contact.ContactHistory;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
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
 * An action that launches {@link WipeOutContactHistoryPiiPipeline} to wipe out Personal
 * Identifiable Information (PII) fields of {@link ContactHistory} entities.
 *
 * <p>{@link ContactHistory} entities should be retained in the database for only certain amount of
 * time.
 */
@Action(
    service = GaeService.BACKEND,
    path = WipeOutContactHistoryPiiAction.PATH,
    auth = Auth.AUTH_ADMIN)
public class WipeOutContactHistoryPiiAction implements Runnable {

  public static final String PATH = "/_dr/task/wipeOutContactHistoryPii";
  public static final String PARAM_CUTOFF_TIME = "wipeoutTime";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String PIPELINE_NAME = "wipe_out_contact_history_pii_pipeline";

  private final Clock clock;
  private final boolean isDryRun;
  private final Optional<DateTime> maybeCutoffTime;
  private final int minMonthsBeforeWipeOut;
  private final String stagingBucketUrl;
  private final String projectId;
  private final String jobRegion;
  private final Dataflow dataflow;
  private final Response response;

  @Inject
  public WipeOutContactHistoryPiiAction(
      Clock clock,
      @Parameter(PARAM_DRY_RUN) boolean isDryRun,
      @Parameter(PARAM_CUTOFF_TIME) Optional<DateTime> maybeCutoffTime,
      @Config("minMonthsBeforeWipeOut") int minMonthsBeforeWipeOut,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      Dataflow dataflow,
      Response response) {
    this.clock = clock;
    this.isDryRun = isDryRun;
    this.maybeCutoffTime = maybeCutoffTime;
    this.minMonthsBeforeWipeOut = minMonthsBeforeWipeOut;
    this.stagingBucketUrl = stagingBucketUrl;
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.dataflow = dataflow;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    DateTime cutoffTime =
        maybeCutoffTime.orElse(clock.nowUtc().minusMonths(minMonthsBeforeWipeOut));
    LaunchFlexTemplateParameter launchParameter =
        new LaunchFlexTemplateParameter()
            .setJobName(
                createJobName(
                    String.format(
                        "contact-history-pii-wipeout-%s",
                        cutoffTime.toString("yyyy-MM-dd't'HH-mm-ss'z'")),
                    clock))
            .setContainerSpecGcsPath(
                String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
            .setParameters(
                ImmutableMap.of(
                    "registryEnvironment",
                    RegistryEnvironment.get().name(),
                    "cutoffTime",
                    cutoffTime.toString("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
                    "isDryRun",
                    Boolean.toString(isDryRun)));
    logger.atInfo().log(
        "Launching Beam pipeline to wipe out all PII of contact history entities prior to %s%s.",
        cutoffTime, " in dry run mode");
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
              "Launched contact history PII wipeout pipeline: %s",
              launchResponse.getJob().getId()));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Pipeline Launch failed");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
    }
  }
}
