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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.reporting.ReportingModule.PARAM_DATE;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.Job;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.template.soy.parseinfo.SoyTemplateInfo;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.config.RegistryConfig.Config;
import google.registry.reporting.ReportingModule;
import google.registry.reporting.spec11.soy.Spec11EmailSoyInfo;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.joda.time.LocalDate;
import org.json.JSONException;

/**
 * Retries until a {@code Dataflow} job with a given {@code jobId} completes, continuing the Spec11
 * pipeline accordingly.
 *
 * <p>This calls {@link Spec11EmailUtils#emailSpec11Reports(LocalDate, SoyTemplateInfo, String,
 * ImmutableSet)} on success or {@link Spec11EmailUtils#sendAlertEmail(String, String)} on failure.
 */
@Action(
    service = Action.Service.BACKEND,
    path = PublishSpec11ReportAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class PublishSpec11ReportAction implements Runnable {

  static final String PATH = "/_dr/task/publishSpec11";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String JOB_DONE = "JOB_STATE_DONE";
  private static final String JOB_FAILED = "JOB_STATE_FAILED";

  private final String projectId;
  private final String jobRegion;
  private final String registryName;
  private final String jobId;
  private final Spec11EmailUtils emailUtils;
  private final Spec11RegistrarThreatMatchesParser spec11RegistrarThreatMatchesParser;
  private final Dataflow dataflow;
  private final Response response;
  private final LocalDate date;

  @Inject
  PublishSpec11ReportAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Config("registryName") String registryName,
      @Parameter(ReportingModule.PARAM_JOB_ID) String jobId,
      Spec11EmailUtils emailUtils,
      Spec11RegistrarThreatMatchesParser spec11RegistrarThreatMatchesParser,
      Dataflow dataflow,
      Response response,
      @Parameter(PARAM_DATE) LocalDate date) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.registryName = registryName;
    this.jobId = jobId;
    this.emailUtils = emailUtils;
    this.spec11RegistrarThreatMatchesParser = spec11RegistrarThreatMatchesParser;
    this.dataflow = dataflow;
    this.response = response;
    this.date = date;
  }

  @Override
  public void run() {
    try {
      logger.atInfo().log("Starting publish job.");
      Job job = dataflow.projects().locations().jobs().get(projectId, jobRegion, jobId).execute();
      String state = job.getCurrentState();
      switch (state) {
        case JOB_DONE:
          logger.atInfo().log("Dataflow job %s finished successfully, publishing results.", jobId);
          response.setStatus(SC_OK);
          if (shouldSendMonthlySpec11Email()) {
            sendMonthlyEmail();
          } else {
            Optional<LocalDate> previousDate =
                spec11RegistrarThreatMatchesParser.getPreviousDateWithMatches(date);
            if (previousDate.isPresent()) {
              processDailyDiff(previousDate.get());
            } else {
              emailUtils.sendAlertEmail(
                  String.format("Spec11 Diff Error %s", date),
                  String.format(
                      "Could not find a previous file within the past month of %s", date));
              response.setStatus(SC_NO_CONTENT);
            }
          }
          break;
        case JOB_FAILED:
          logger.atSevere().log("Dataflow job %s finished unsuccessfully.", jobId);
          response.setStatus(SC_NO_CONTENT);
          emailUtils.sendAlertEmail(
              String.format("Spec11 Dataflow Pipeline Failure %s", date),
              String.format("Spec11 %s job %s ended in status failure.", date, jobId));
          break;
        default:
          logger.atInfo().log("Job in non-terminal state %s, retrying:", state);
          response.setStatus(SC_NOT_MODIFIED);
          break;
      }
    } catch (IOException | JSONException e) {
      logger.atSevere().withCause(e).log("Failed to publish Spec11 reports.");
      emailUtils.sendAlertEmail(
          String.format("Spec11 Publish Failure %s", date),
          String.format("Spec11 %s publish action failed due to %s", date, e.getMessage()));
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Template launch failed: %s", e.getMessage()));
    }
  }

  private void sendMonthlyEmail() throws IOException, JSONException {
    ImmutableSet<RegistrarThreatMatches> monthlyMatchesSet =
        spec11RegistrarThreatMatchesParser.getRegistrarThreatMatches(date);
    String subject = String.format("%s Monthly Threat Detector [%s]", registryName, date);
    emailUtils.emailSpec11Reports(
        date, Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL, subject, monthlyMatchesSet);
  }

  private void processDailyDiff(LocalDate previousDate) throws IOException, JSONException {
    ImmutableSet<RegistrarThreatMatches> previousMatches =
        spec11RegistrarThreatMatchesParser.getRegistrarThreatMatches(previousDate);
    ImmutableSet<RegistrarThreatMatches> currentMatches =
        spec11RegistrarThreatMatchesParser.getRegistrarThreatMatches(date);
    String dailySubject = String.format("%s Daily Threat Detector [%s]", registryName, date);
    emailUtils.emailSpec11Reports(
        date,
        Spec11EmailSoyInfo.DAILY_SPEC_11_EMAIL,
        dailySubject,
        getNewMatches(previousMatches, currentMatches));
  }

  private ImmutableSet<RegistrarThreatMatches> getNewMatches(
      ImmutableSet<RegistrarThreatMatches> previousMatchesSet,
      ImmutableSet<RegistrarThreatMatches> currentMatchesSet) {
    ImmutableMap<String, ImmutableSet<ThreatMatch>> previousMatchesByEmail =
        groupByKeyAndFlatMap(previousMatchesSet);
    ImmutableMap<String, ImmutableSet<ThreatMatch>> currentMatchesByEmail =
        groupByKeyAndFlatMap(currentMatchesSet);
    ImmutableSet.Builder<RegistrarThreatMatches> resultsBuilder = ImmutableSet.builder();
    for (String email : currentMatchesByEmail.keySet()) {
      // Only include matches in the result if they're non-empty
      Set<ThreatMatch> difference =
          Sets.difference(
              currentMatchesByEmail.get(email),
              previousMatchesByEmail.getOrDefault(email, ImmutableSet.of()));
      if (!difference.isEmpty()) {
        resultsBuilder.add(RegistrarThreatMatches.create(email, ImmutableList.copyOf(difference)));
      }
    }
    return resultsBuilder.build();
  }

  private ImmutableMap<String, ImmutableSet<ThreatMatch>> groupByKeyAndFlatMap(
      ImmutableSet<RegistrarThreatMatches> registrarThreatMatches) {
    // Group by email address then flat-map all of the ThreatMatch objects together
    return ImmutableMap.copyOf(
        Maps.transformValues(
            Multimaps.index(registrarThreatMatches, RegistrarThreatMatches::clientId).asMap(),
            registrarThreatMatchesCollection ->
                registrarThreatMatchesCollection.stream()
                    .flatMap(matches -> matches.threatMatches().stream())
                    .collect(toImmutableSet())));
  }

  private boolean shouldSendMonthlySpec11Email() {
    return date.getDayOfMonth() == 2;
  }
}
