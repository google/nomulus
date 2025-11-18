// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.batch.BatchModule.PARAM_FAST;
import static google.registry.beam.BeamUtils.createJobName;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;

/**
 * Starts a Dataflow pipeline that resaves all EPP resources projected to the current time.
 *
 * <p>This is useful for a few situations. First, as a fallback option for resource transfers that
 * have expired pending transfers (this will resolve them), just in case the enqueued action fails.
 * Second, it will reflect domain autorenews that have happened. Third, it will remove any expired
 * grace periods.
 *
 * <p>There's also the general principle that it's good to have the data in the database remain as
 * current as is reasonably possible.
 *
 * <p>If the <code>?isFast=true</code> query string parameter is passed as true, the pipeline will
 * only attempt to load, project, and resave entities where we expect one of the previous situations
 * has occurred. Otherwise, we will load, project, and resave all EPP resources.
 *
 * <p>This runs the {@link google.registry.beam.resave.ResaveAllEppResourcesPipeline}.
 */
@Action(
    service = Action.Service.BACKEND,
    path = ResaveAllEppResourcesPipelineAction.PATH,
    auth = Auth.AUTH_ADMIN)
public class ResaveAllEppResourcesPipelineAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/resaveAllEppResourcesPipeline";
  static final String PIPELINE_NAME = "resave_all_epp_resources_pipeline";

  private final String projectId;
  private final String jobRegion;
  private final String stagingBucketUrl;
  private final boolean fast;
  private final Clock clock;
  private final Response response;
  private final Dataflow dataflow;

  @Inject
  ResaveAllEppResourcesPipelineAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      @Parameter(PARAM_FAST) boolean fast,
      Clock clock,
      Response response,
      Dataflow dataflow) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.stagingBucketUrl = stagingBucketUrl;
    this.fast = fast;
    this.clock = clock;
    this.response = response;
    this.dataflow = dataflow;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    logger.atInfo().log("Launching ResaveAllEppResourcesPipeline");
    try {
      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName("resave-all-epp-resources", clock))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(
                  new ImmutableMap.Builder<String, String>()
                      .put(PARAM_FAST, Boolean.toString(fast))
                      .put("registryEnvironment", RegistryEnvironment.get().name())
                      .build());
      LaunchFlexTemplateResponse launchResponse =
          dataflow
              .projects()
              .locations()
              .flexTemplates()
              .launch(
                  projectId,
                  jobRegion,
                  new LaunchFlexTemplateRequest().setLaunchParameter(parameter))
              .execute();
      logger.atInfo().log("Got response: %s", launchResponse.getJob().toPrettyString());
      String jobId = launchResponse.getJob().getId();
      response.setStatus(SC_OK);
      response.setPayload(String.format("Launched resaveAllEppResources pipeline: %s", jobId));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Template Launch failed.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
    }
  }
}
