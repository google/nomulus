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

package google.registry.reporting.icann;

import static com.google.common.base.Throwables.getRootCause;
import static google.registry.reporting.icann.IcannReportingModule.PARAM_REPORT_TYPES;
import static google.registry.reporting.icann.IcannReportingModule.PARAM_SUBDIR;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.bigquery.BigqueryJobFailureException;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.registry.Registries;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import org.joda.time.Duration;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;

/**
 * Action that generates monthly ICANN activity and transactions reports.
 *
 * <p>The reports are stored in GCS under gs://[project-id]-reporting/[subdir]. We also store a
 * MANIFEST.txt file that contains a list of filenames generated, to facilitate subsequent uploads.
 *
 * <p>Parameters:
 *
 * <p>yearMonth: the reporting month in yyyy-MM format. Defaults to the previous month at runtime
 * (i.e. a run on 2017-09-01 defaults to 2017-08's reports).
 *
 * <p>subdir: the subdirectory of gs://[project-id]-reporting/ to upload to. For example:
 * "manual/dir" means reports will be stored under gs://[project-id]-reporting/manual/dir. Defaults
 * to "icann/monthly/[yearMonth]".
 *
 * <p>reportTypes: the type of reports to generate. You can specify either 'activity' or
 * 'transactions'. If none specified - defaults to generating both.
 */
@Action(
    service = Action.Service.BACKEND,
    path = IcannReportingStagingAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_ONLY)
public final class IcannReportingStagingAction implements Runnable {

  static final String PATH = "/_dr/task/icannReportingStaging";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String CRON_QUEUE = "retryable-cron-tasks";
  private static final String DEFAULT_SUBDIR = "icann/monthly";

  @Inject YearMonth yearMonth;
  @Inject @Parameter(PARAM_SUBDIR) Optional<String> overrideSubdir;
  @Inject @Parameter(PARAM_REPORT_TYPES) ImmutableSet<ReportType> reportTypes;
  @Inject IcannReportingStager stager;
  @Inject Retrier retrier;
  @Inject Response response;
  @Inject @Config("gSuiteOutgoingEmailAddress") InternetAddress sender;
  @Inject @Config("alertRecipientEmailAddress") InternetAddress recipient;
  @Inject SendEmailService emailService;

  @Inject IcannReportingStagingAction() {}

  @Override
  public void run() {
    try {
      String subdir = getSubdir(yearMonth);
      retrier.callWithRetry(
          () -> {
            ImmutableList.Builder<String> manifestedFilesBuilder = new ImmutableList.Builder<>();
            for (ReportType reportType : reportTypes) {
              manifestedFilesBuilder.addAll(stager.stageReports(yearMonth, subdir, reportType));
            }
            ImmutableList<String> manifestedFiles = manifestedFilesBuilder.build();
            stager.createAndUploadManifest(subdir, manifestedFiles);

            logger.atInfo().log("Completed staging %d report files.", manifestedFiles.size());
            emailService.sendEmail(
                EmailMessage.newBuilder()
                    .setSubject("ICANN Monthly report staging summary [SUCCESS]")
                    .setBody(
                        String.format(
                            "Completed staging the following %d ICANN reports:\n%s",
                            manifestedFiles.size(), Joiner.on('\n').join(manifestedFiles)))
                    .addRecipient(recipient)
                    .setFrom(sender)
                    .build());

            response.setStatus(SC_OK);
            response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
            response.setPayload("Completed staging action.");

            logger.atInfo().log("Enqueueing report upload :");
            ImmutableSet<String> tlds = Registries.getTlds();
            for (String tld : tlds) {
              TaskOptions uploadTask =
                  TaskOptions.Builder.withUrl(IcannReportingUploadAction.PATH)
                      .method(Method.POST)
                      .countdownMillis(Duration.standardMinutes(2).getMillis())
                      .param(PARAM_SUBDIR, subdir)
                      .param(RequestParameters.PARAM_TLD, tld);
              QueueFactory.getQueue(CRON_QUEUE).add(uploadTask);
            }
            return null;
          },
          BigqueryJobFailureException.class);
    } catch (Throwable e) {
      emailService.sendEmail(
          EmailMessage.create(
              "ICANN Monthly report staging summary [FAILURE]",
              String.format(
                  "Staging failed due to %s, check logs for more details.", getRootCause(e)),
              recipient,
              sender));
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(
          String.format(
              "Staging failed due to %s",
              getRootCause(e).toString()));
      throw new RuntimeException("Staging action failed.", e);
    }
  }

  String getSubdir(YearMonth yearMonth) {
    return IcannReportingModule.checkSubdirValid(
        overrideSubdir.orElse(
            String.format(
                "%s/%s", DEFAULT_SUBDIR, DateTimeFormat.forPattern("yyyy-MM").print(yearMonth))));
  }
}
