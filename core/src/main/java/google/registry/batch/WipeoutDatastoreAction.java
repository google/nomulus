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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 * Wipes out all Cloud Datastore data in a Nomulus GCP environment.
 *
 * <p>This class is created for the QA environment, where migration testing with production data
 * will happen. A regularly scheduled wipeout is a prerequisite to using production data there.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/wipeOutDatastore",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class WipeoutDatastoreAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String PIPELINE_NAME = "bulk_delete_datastore_pipeline";

  // As a short-lived class, hardcode allowed projects here instead of using config files.
  private static final ImmutableSet<String> ALLOWED_PROJECTS =
      ImmutableSet.of("domain-registry-qa");

  private final String projectId;
  private final String jobRegion;
  private final Response response;
  private final Dataflow dataflow;
  private final String stagingBucketUrl;

  @Inject
  WipeoutDatastoreAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      Response response,
      Dataflow dataflow) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.stagingBucketUrl = stagingBucketUrl;
    this.response = response;
    this.dataflow = dataflow;
  }

  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);

    if (!ALLOWED_PROJECTS.contains(projectId)) {
      response.setStatus(SC_FORBIDDEN);
      response.setPayload("Wipeout is not allowed in " + projectId);
      return;
    }

    try {
      LaunchFlexTemplateParameter parameters =
          new LaunchFlexTemplateParameter()
              // Job name must be unique and in [-a-z0-9].
              .setJobName(
                  "bulk-delete-datastore-"
                      + DateTime.now(DateTimeZone.UTC).toString("yyyy-MM-dd'T'HH-mm-ss'Z'"))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(ImmutableMap.of("kindsToDelete", "*"));
      LaunchFlexTemplateResponse launchResponse =
          dataflow
              .projects()
              .locations()
              .flexTemplates()
              .launch(
                  projectId,
                  jobRegion,
                  new LaunchFlexTemplateRequest().setLaunchParameter(parameters))
              .execute();
      response.setStatus(SC_OK);
      response.setPayload("Launched " + launchResponse.getJob().getName());
    } catch (Exception e) {
      String msg = String.format("Failed to launch %s.", PIPELINE_NAME);
      logger.atSevere().withCause(e).log(msg);
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(msg);
    }
  }
}
