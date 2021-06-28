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

package google.registry.reporting.billing;

import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.reporting.ReportingModule.DATABASE;
import static google.registry.reporting.ReportingUtils.enqueueBeamReportingTask;
import static google.registry.reporting.billing.BillingModule.PARAM_SHOULD_PUBLISH;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.reporting.ReportingModule;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.io.IOException;
import java.util.Map;
import javax.inject.Inject;
import org.joda.time.YearMonth;

/**
 * Invokes the {@code InvoicingPipeline} beam template via the REST api, and enqueues the {@link
 * PublishInvoicesAction} to publish the subsequent output.
 *
 * <p>This action runs the {@link google.registry.beam.invoicing.InvoicingPipeline} beam flex
 * template. The pipeline then generates invoices for the month and stores them on GCS.
 */
@Action(
    service = Action.Service.BACKEND,
    path = GenerateInvoicesAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class GenerateInvoicesAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/generateInvoices";
  static final String PIPELINE_NAME = "invoicing_pipeline";

  private final String projectId;
  private final String jobRegion;
  private final String stagingBucketUrl;
  private final String billingBucketUrl;
  private final String invoiceFilePrefix;
  private final boolean shouldPublish;
  private final YearMonth yearMonth;
  private final BillingEmailUtils emailUtils;
  private final Clock clock;
  private final Response response;
  private final Dataflow dataflow;
  private final String database;

  @Inject
  GenerateInvoicesAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("beamStagingBucketUrl") String stagingBucketUrl,
      @Config("billingBucketUrl") String billingBucketUrl,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @Parameter(PARAM_SHOULD_PUBLISH) boolean shouldPublish,
      @Parameter(DATABASE) String database,
      YearMonth yearMonth,
      BillingEmailUtils emailUtils,
      Clock clock,
      Response response,
      Dataflow dataflow) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.stagingBucketUrl = stagingBucketUrl;
    if (tm().isOfy() && database.equals("CLOUD_SQL")) {
      billingBucketUrl = billingBucketUrl.concat("-sql");
    }
    this.billingBucketUrl = billingBucketUrl;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.shouldPublish = shouldPublish;
    this.database = database;
    this.yearMonth = yearMonth;
    this.emailUtils = emailUtils;
    this.clock = clock;
    this.response = response;
    this.dataflow = dataflow;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    logger.atInfo().log("Launching invoicing pipeline for %s", yearMonth);
    try {
      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName("invoicing", clock))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(
                  ImmutableMap.of(
                      "yearMonth",
                      yearMonth.toString("yyyy-MM"),
                      "invoiceFilePrefix",
                      invoiceFilePrefix,
                      "database",
                      database,
                      "billingBucketUrl",
                      billingBucketUrl));
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
      if (shouldPublish) {
        Map<String, String> beamTaskParameters =
            ImmutableMap.of(
                ReportingModule.PARAM_JOB_ID,
                jobId,
                ReportingModule.PARAM_YEAR_MONTH,
                yearMonth.toString());
        enqueueBeamReportingTask(PublishInvoicesAction.PATH, beamTaskParameters);
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Template Launch failed");
      emailUtils.sendAlertEmail(String.format("Template Launch failed due to %s", e.getMessage()));
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Template launch failed: %s", e.getMessage()));
      return;
    }
    response.setStatus(SC_OK);
    response.setPayload("Launched dataflow template.");
  }
}
