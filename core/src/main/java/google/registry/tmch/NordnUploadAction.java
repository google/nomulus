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

package google.registry.tmch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.appengine.api.urlfetch.FetchOptions.Builder.validateCertificate;
import static com.google.appengine.api.urlfetch.HTTPMethod.POST;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static google.registry.tmch.LordnTaskUtils.COLUMNS_CLAIMS;
import static google.registry.tmch.LordnTaskUtils.COLUMNS_SUNRISE;
import static google.registry.util.UrlFetchUtils.getHeaderFirst;
import static google.registry.util.UrlFetchUtils.setPayloadMultipart;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;

import com.google.appengine.api.taskqueue.LeaseOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.apphosting.api.DeadlineExceededException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import google.registry.util.UrlFetchException;
import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that reads the NORDN pull queues, uploads claims and sunrise marks data to TMCH, and
 * enqueues subsequent upload verification tasks. A unique actionLogId is generated and passed along
 * to the verify action so that connected verify tasks can be identified by looking at logs.
 *
 * @see NordnVerifyAction
 */
@Action(
    service = Action.Service.BACKEND,
    path = NordnUploadAction.PATH,
    method = Action.Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class NordnUploadAction implements Runnable {

  static final String PATH = "/_dr/task/nordnUpload";
  static final String LORDN_PHASE_PARAM = "lordn-phase";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration LEASE_PERIOD = Duration.standardHours(1);

  /**
   * A unique (enough) id that is outputted in log lines to make it clear which log lines are
   * associated with a given invocation of the NordnUploadAction in the event that multiple
   * instances execute simultaneously.
   */
  private final String actionLogId = String.valueOf(1000000000 + new Random().nextInt(1000000000));

  @Inject Clock clock;
  @Inject Retrier retrier;
  @Inject SecureRandom random;
  @Inject LordnRequestInitializer lordnRequestInitializer;
  @Inject URLFetchService fetchService;
  @Inject @Config("tmchMarksdbUrl") String tmchMarksdbUrl;
  @Inject @Parameter(LORDN_PHASE_PARAM) String phase;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject TaskQueueUtils taskQueueUtils;
  @Inject NordnUploadAction() {}

  /**
   * These LORDN parameter names correspond to the relative paths in LORDN URLs and cannot be
   * changed on our end.
   */
  private static final String PARAM_LORDN_PHASE_SUNRISE = "sunrise";
  private static final String PARAM_LORDN_PHASE_CLAIMS = "claims";

  /** How long to wait before attempting to verify an upload by fetching the log. */
  private static final Duration VERIFY_DELAY = Duration.standardMinutes(30);

  @Override
  public void run() {
    try {
      processLordnTasks();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Converts a list of queue tasks, each containing a row of CSV data, into a single newline-
   * delimited String.
   */
  static String convertTasksToCsv(List<TaskHandle> tasks, DateTime now, String columns) {
    String header = String.format("1,%s,%d\n%s\n", now, tasks.size(), columns);
    StringBuilder csv = new StringBuilder(header);
    for (TaskHandle task : checkNotNull(tasks)) {
      String payload = new String(task.getPayload(), UTF_8);
      if (!Strings.isNullOrEmpty(payload)) {
        csv.append(payload).append("\n");
      }
    }
    return csv.toString();
  }

  /** Leases and returns all tasks from the queue with the specified tag tld, in batches. */
  List<TaskHandle> loadAllTasks(Queue queue, String tld) {
    ImmutableList.Builder<TaskHandle> allTasks = new ImmutableList.Builder<>();
    while (true) {
      List<TaskHandle> tasks =
          retrier.callWithRetry(
              () ->
                  queue.leaseTasks(
                      LeaseOptions.Builder.withTag(tld)
                          .leasePeriod(LEASE_PERIOD.getMillis(), TimeUnit.MILLISECONDS)
                          .countLimit(TaskQueueUtils.getBatchSize())),
              TransientFailureException.class,
              DeadlineExceededException.class);
      if (tasks.isEmpty()) {
        return allTasks.build();
      }
      allTasks.addAll(tasks);
    }
  }

  private void processLordnTasks() throws IOException {
    checkArgument(phase.equals(PARAM_LORDN_PHASE_SUNRISE)
        || phase.equals(PARAM_LORDN_PHASE_CLAIMS),
        "Invalid phase specified to Nordn servlet: %s.", phase);
    DateTime now = clock.nowUtc();
    Queue queue =
        getQueue(
            phase.equals(PARAM_LORDN_PHASE_SUNRISE)
                ? LordnTaskUtils.QUEUE_SUNRISE
                : LordnTaskUtils.QUEUE_CLAIMS);
    String columns = phase.equals(PARAM_LORDN_PHASE_SUNRISE) ? COLUMNS_SUNRISE : COLUMNS_CLAIMS;
    List<TaskHandle> tasks = loadAllTasks(queue, tld);
    if (!tasks.isEmpty()) {
      String csvData = convertTasksToCsv(tasks, now, columns);
      uploadCsvToLordn(String.format("/LORDN/%s/%s", tld, phase), csvData);
      taskQueueUtils.deleteTasks(queue, tasks);
    }
  }

  /**
   * Upload LORDN file to MarksDB.
   *
   * <p>Idempotency: If the exact same LORDN report is uploaded twice, the MarksDB server will
   * return the same confirmation number.
   *
   * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3">
   *     TMCH functional specifications - LORDN File</a>
   */
  private void uploadCsvToLordn(String urlPath, String csvData) throws IOException {
    String url = tmchMarksdbUrl + urlPath;
    logger.atInfo().log(
        "LORDN upload task %s: Sending to URL: %s ; data: %s", actionLogId, url, csvData);
    HTTPRequest req = new HTTPRequest(new URL(url), POST, validateCertificate().setDeadline(60d));
    lordnRequestInitializer.initialize(req, tld);
    setPayloadMultipart(req, "file", "claims.csv", CSV_UTF_8, csvData, random);
    HTTPResponse rsp;
    try {
      rsp = fetchService.fetch(req);
    } catch (IOException e) {
      throw new IOException(
          String.format("Error connecting to MarksDB at URL %s", url), e);
    }
    if (logger.atInfo().isEnabled()) {
      String response =
          (rsp.getContent() == null) ? "(null)" : new String(rsp.getContent(), US_ASCII);
      logger.atInfo().log(
          "LORDN upload task %s response: HTTP response code %d, response data: %s",
          actionLogId, rsp.getResponseCode(), response);
    }
    if (rsp.getResponseCode() != SC_ACCEPTED) {
      throw new UrlFetchException(
          String.format(
              "LORDN upload task %s error: Failed to upload LORDN claims to MarksDB", actionLogId),
          req,
          rsp);
    }
    Optional<String> location = getHeaderFirst(rsp, LOCATION);
    if (!location.isPresent()) {
      throw new UrlFetchException(
          String.format(
              "LORDN upload task %s error: MarksDB failed to provide a Location header",
              actionLogId),
          req,
          rsp);
    }
    getQueue(NordnVerifyAction.QUEUE).add(makeVerifyTask(new URL(location.get())));
  }

  private TaskOptions makeVerifyTask(URL url) {
    // The actionLogId is used to uniquely associate the verify task back to the upload task.
    return withUrl(NordnVerifyAction.PATH)
        .header(NordnVerifyAction.URL_HEADER, url.toString())
        .header(NordnVerifyAction.HEADER_ACTION_LOG_ID, actionLogId)
        .param(RequestParameters.PARAM_TLD, tld)
        .countdownMillis(VERIFY_DELAY.getMillis());
  }
}
