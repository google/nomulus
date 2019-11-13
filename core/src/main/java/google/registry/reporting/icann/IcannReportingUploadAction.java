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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.reporting.icann.IcannReportingModule.MANIFEST_FILE_NAME;
import static google.registry.reporting.icann.IcannReportingModule.PARAM_SUBDIR;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registries;
import google.registry.model.registry.Registry;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that uploads the monthly activity/transactions reports from GCS to ICANN via an HTTP PUT.
 *
 * <p>This should be run after {@link IcannReportingStagingAction}, which writes out the month's
 * reports and a MANIFEST.txt file. This action reads the filenames from the MANIFEST.txt, and
 * attempts to upload every file in the manifest to ICANN's endpoint.
 *
 * <p>Parameters:
 *
 * <p>subdir: the subdirectory of gs://[project-id]-reporting/ to retrieve reports from. For
 * example: "manual/dir" means reports will be stored under gs://[project-id]-reporting/manual/dir.
 * Defaults to "icann/monthly/[last month in yyyy-MM format]".
 */
@Action(
    service = Action.Service.BACKEND,
    path = IcannReportingUploadAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class IcannReportingUploadAction implements Runnable {

  static final String PATH = "/_dr/task/icannReportingUpload";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  @Config("reportingBucket")
  String reportingBucket;

  @Inject @Parameter(PARAM_SUBDIR) String subdir;

  @Inject GcsUtils gcsUtils;
  @Inject IcannHttpReporter icannReporter;
  @Inject Retrier retrier;
  @Inject Response response;
  @Inject @Config("gSuiteOutgoingEmailAddress") InternetAddress sender;
  @Inject @Config("alertRecipientEmailAddress") InternetAddress recipient;
  @Inject SendEmailService emailService;
  @Inject Clock clock;
  @Inject LockHandler lockHandler;

  @Inject
  IcannReportingUploadAction() {}

  @Override
  public void run() {
    Callable<Void> lockrunner =
        () -> {
          ImmutableMap.Builder<String, Boolean> reportSummaryBuilder = new ImmutableMap.Builder<>();

          // Load all activity cursors
          HashMap<Cursor, List<Object>> Cursors = loadCursors();

          // If cursor date is before today or today, upload the corresponding activity report
          Cursors.entrySet().stream()
              .filter(entry -> getCursorTimeOrStartOfTime(entry.getKey()).isBefore(clock.nowUtc()))
              .forEach(
                  (entry) -> {
                    DateTime cursorTime = getCursorTimeOrStartOfTime(entry.getKey());
                    uploadReport(
                        cursorTime,
                        (CursorType) entry.getValue().get(0),
                        (entry.getValue().size() == 2 ? entry.getValue().get(1).toString() : null),
                        reportSummaryBuilder);
                  });
          // Send email of which reports were uploaded
          emailUploadResults(reportSummaryBuilder.build());
          response.setStatus(SC_OK);
          response.setContentType(PLAIN_TEXT_UTF_8);
          return null;
        };

    String lockname = "IcannReportingUploadAction";
    if (!lockHandler.executeWithLocks(lockrunner, null, Duration.standardHours(2), lockname)) {
      throw new ServiceUnavailableException("Lock for IcannReportingUploadAction already in use.");
    }
  }

  /** Uploads the report and rolls forward the cursor for that report. */
  private void uploadReport(
      DateTime cursorTime,
      CursorType cursorType,
      String tldStr,
      ImmutableMap.Builder<String, Boolean> reportSummaryBuilder) {
    String reportBucketname = String.format("%s/%s", reportingBucket, subdir);
    String filename = getFileName(cursorType, cursorTime, tldStr);
    final GcsFilename gcsFilename = new GcsFilename(reportBucketname, filename);
    logger.atInfo().log("Reading ICANN report %s from bucket %s", filename, reportBucketname);
    // Check that the report exists
    try {
      verifyFileExists(gcsFilename);
    } catch (IllegalArgumentException e) {
      if (clock.nowUtc().dayOfMonth().get() == 1) {
        logger.atInfo().withCause(e).log(
            "Could not upload "
                + cursorType
                + " report for "
                + tldStr
                + " because file "
                + filename
                + " did not exist. This report may not have been staged yet.");
      } else {
        logger.atSevere().withCause(e).log(
            "Could not upload "
                + cursorType
                + " report for "
                + tldStr
                + " because file "
                + filename
                + " did not exist.");
      }
      reportSummaryBuilder.put(filename, false);
      return;
    }

    // Upload the report
    boolean success = false;
    try {
      success =
          retrier.callWithRetry(
              () -> {
                final byte[] payload = readBytesFromGcs(gcsFilename);
                return icannReporter.send(payload, filename);
              },
              IcannReportingUploadAction::isUploadFailureRetryable);
    } catch (RuntimeException e) {
      logger.atWarning().withCause(e).log("Upload to %s failed.", gcsFilename);
    }
    reportSummaryBuilder.put(filename, success);

    // Set cursor to first day of next month if the upload succeeded
    if (success) {
      Cursor newCursor;
      if (cursorType.equals(CursorType.ICANN_UPLOAD_MANIFEST)) {
        newCursor =
            Cursor.createGlobal(
                cursorType, cursorTime.withTimeAtStartOfDay().withDayOfMonth(1).plusMonths(1));
      } else {
        newCursor =
            Cursor.create(
                cursorType,
                cursorTime.withTimeAtStartOfDay().withDayOfMonth(1).plusMonths(1),
                Registry.get(tldStr));
      }
      tm().transact(() -> ofy().save().entity(newCursor));
    }
  }

  private String getFileName(CursorType cursorType, DateTime cursorTime, String tld) {
    if (cursorType.equals(CursorType.ICANN_UPLOAD_MANIFEST)) {
      return MANIFEST_FILE_NAME;
    }
    String filename =
        tld
            + (cursorType.equals(CursorType.ICANN_UPLOAD_ACTIVITY)
                ? "-activity-"
                : "-transactions-")
            + cursorTime.year().get()
            + String.format("%02d", cursorTime.monthOfYear().get())
            + ".csv";
    return filename;
  }

  /** Returns a map of each cursor to the CursorType and tld. */
  private HashMap<Cursor, List<Object>> loadCursors() {
    Map<Key<Cursor>, String> activityKeyMap =
        Registries.getTlds().stream()
            .map(Registry::get)
            .collect(
                toImmutableMap(
                    r -> Cursor.createKey(CursorType.ICANN_UPLOAD_ACTIVITY, r),
                    r -> r.getTldStr()));
    Map<Key<Cursor>, String> transactionKeyMap =
        Registries.getTlds().stream()
            .map(Registry::get)
            .collect(
                toImmutableMap(
                    r -> Cursor.createKey(CursorType.ICANN_UPLOAD_TX, r), r -> r.getTldStr()));
    Set<Key<Cursor>> keys = new HashSet<>();
    keys.addAll(activityKeyMap.keySet());
    keys.addAll(transactionKeyMap.keySet());
    keys.add(Cursor.createGlobalKey(CursorType.ICANN_UPLOAD_MANIFEST));
    Map<Key<Cursor>, Cursor> cursorMap = ofy().load().keys(keys);
    HashMap<Cursor, List<Object>> cursors = new LinkedHashMap<>();
    activityKeyMap.forEach(
        (key, tld) -> {
          List<Object> list = new ArrayList<>();
          list.add(CursorType.ICANN_UPLOAD_ACTIVITY);
          list.add(tld);
          cursors.put(cursorMap.get(key), list);
        });
    transactionKeyMap.forEach(
        (key, tld) -> {
          List<Object> list = new ArrayList<>();
          list.add(CursorType.ICANN_UPLOAD_TX);
          list.add(tld);
          cursors.put(cursorMap.get(key), list);
        });
    cursors.put(
        cursorMap.get(Cursor.createGlobalKey(CursorType.ICANN_UPLOAD_MANIFEST)),
        Collections.singletonList(CursorType.ICANN_UPLOAD_MANIFEST));
    return cursors;
  }

  /** Don't retry when reports are already uploaded or can't be uploaded. */
  private static final String ICANN_UPLOAD_PERMANENT_ERROR_MESSAGE =
      "A report for that month already exists, the cut-off date already passed.";

  /** Don't retry when the IP address isn't whitelisted, as retries go through the same IP. */
  private static final Pattern ICANN_UPLOAD_WHITELIST_ERROR =
      Pattern.compile("Your IP address .+ is not allowed to connect");

  /** Predicate to retry uploads on IOException, so long as they aren't non-retryable errors. */
  private static boolean isUploadFailureRetryable(Throwable e) {
    return (e instanceof IOException)
        && !e.getMessage().contains(ICANN_UPLOAD_PERMANENT_ERROR_MESSAGE)
        && !ICANN_UPLOAD_WHITELIST_ERROR.matcher(e.getMessage()).matches();
  }

  private void emailUploadResults(ImmutableMap<String, Boolean> reportSummary) {
    String subject = String.format(
        "ICANN Monthly report upload summary: %d/%d succeeded",
        reportSummary.values().stream().filter((b) -> b).count(), reportSummary.size());
    String body =
        String.format(
            "Report Filename - Upload status:\n%s",
            reportSummary.entrySet().stream()
                .map(
                    (e) ->
                        String.format("%s - %s", e.getKey(), e.getValue() ? "SUCCESS" : "FAILURE"))
                .collect(Collectors.joining("\n")));
    emailService.sendEmail(EmailMessage.create(subject, body, recipient, sender));
  }

  private byte[] readBytesFromGcs(GcsFilename reportFilename) throws IOException {
    try (InputStream gcsInput = gcsUtils.openInputStream(reportFilename)) {
      return ByteStreams.toByteArray(gcsInput);
    }
  }

  private void verifyFileExists(GcsFilename gcsFilename) {
    checkArgument(
        gcsUtils.existsAndNotEmpty(gcsFilename),
        "Object %s in bucket %s not found",
        gcsFilename.getObjectName(),
        gcsFilename.getBucketName());
  }
}
