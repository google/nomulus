// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static google.registry.reporting.ReportingModule.PARAM_DATE;
import static google.registry.reporting.ReportingUtils.enqueueBeamReportingTask;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchTemplateParameters;
import com.google.api.services.dataflow.model.LaunchTemplateResponse;
import com.google.api.services.dataflow.model.RuntimeEnvironment;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.reporting.ReportingModule;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.util.Map;
import javax.inject.Inject;
import org.joda.time.LocalDate;

/**
 * Invokes the {@code Spec11Pipeline} Beam template via the REST api.
 *
 * <p>This action runs the {@link google.registry.beam.spec11.Spec11Pipeline} template, which
 * generates the specified month's Spec11 report and stores it on GCS.
 */
@Action(
    service = Action.Service.BACKEND,
    path = GenerateSpec11ReportAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class GenerateSpec11ReportAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/generateSpec11";

  private final String projectId;
  private final String beamBucketUrl;
  private final String spec11TemplateUrl;
  private final String jobZone;
  private final String apiKey;
  private final LocalDate date;
  private final Response response;
  private final Dataflow dataflow;

  @Inject
  GenerateSpec11ReportAction(
      @Config("projectId") String projectId,
      @Config("apacheBeamBucketUrl") String beamBucketUrl,
      @Config("spec11TemplateUrl") String spec11TemplateUrl,
      @Config("defaultJobZone") String jobZone,
      @Key("safeBrowsingAPIKey") String apiKey,
      @Parameter(PARAM_DATE) LocalDate date,
      Response response,
      Dataflow dataflow) {
    this.projectId = projectId;
    this.beamBucketUrl = beamBucketUrl;
    this.spec11TemplateUrl = spec11TemplateUrl;
    this.jobZone = jobZone;
    this.apiKey = apiKey;
    this.date = date;
    this.response = response;
    this.dataflow = dataflow;
  }

  @Override
  public void run() {
    try {
      LaunchTemplateParameters params =
          new LaunchTemplateParameters()
              .setJobName(String.format("spec11_%s", date))
              .setEnvironment(
                  new RuntimeEnvironment()
                      .setZone(jobZone)
                      .setTempLocation(beamBucketUrl + "/temporary"))
              .setParameters(
                  ImmutableMap.of(
                      "safeBrowsingApiKey", apiKey, ReportingModule.PARAM_DATE, date.toString()));
      LaunchTemplateResponse launchResponse =
          dataflow
              .projects()
              .templates()
              .launch(projectId, params)
              .setGcsPath(spec11TemplateUrl)
              .execute();
      Map<String, String> beamTaskParameters =
          ImmutableMap.of(
              ReportingModule.PARAM_JOB_ID,
              launchResponse.getJob().getId(),
              ReportingModule.PARAM_DATE,
              date.toString());
      enqueueBeamReportingTask(PublishSpec11ReportAction.PATH, beamTaskParameters);
      logger.atInfo().log("Got response: %s", launchResponse.getJob().toPrettyString());
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Template Launch failed");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Template launch failed: %s", e.getMessage()));
      return;
    }
    response.setStatus(SC_OK);
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    response.setPayload("Launched Spec11 dataflow template.");
  }
}
