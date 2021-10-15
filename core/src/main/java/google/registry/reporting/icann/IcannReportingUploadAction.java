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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.tld.Registries;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
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
 * reports and a MANIFEST.txt file. This action checks each ICANN_UPLOAD_TX and
 * ICANN_UPLOAD_ACTIVITY cursor and uploads the corresponding report if the cursor time is before
 * now.
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
  private static final String LOCK_NAME = "IcannReportingUploadAction";

  @Inject
  @Config("reportingBucket")
  String reportingBucket;

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
    if (!lockHandler.executeWithLocks(
        this::runWithLock, null, Duration.standardHours(2), LOCK_NAME)) {
      throw new ServiceUnavailableException(String.format("Lock for %s already in use", LOCK_NAME));
    }
  }

  private Void runWithLock() {
    ImmutableMap.Builder<String, Boolean> reportSummaryBuilder = new ImmutableMap.Builder<>();

    ImmutableMap<Cursor, String> cursors = tm().transact(this::loadCursors);

    // If cursor time is before now, upload the corresponding report
    cursors.entrySet().stream()
        .filter(entry -> entry.getKey().getCursorTime().isBefore(clock.nowUtc()))
        .forEach(entry -> uploadReport(entry.getKey(), entry.getValue(), reportSummaryBuilder));
    // Send email of which reports were uploaded
    emailUploadResults(reportSummaryBuilder.build());
    response.setStatus(SC_OK);
    response.setContentType(PLAIN_TEXT_UTF_8);
    return null;
  }

  /** Uploads the report and rolls forward the cursor for that report. */
  private void uploadReport(
      Cursor cursor, String tldStr, ImmutableMap.Builder<String, Boolean> reportSummaryBuilder) {
    DateTime cursorTime = cursor.getCursorTime();
    CursorType cursorType = cursor.getType();
    DateTime cursorTimeMinusMonth = cursorTime.withDayOfMonth(1).minusMonths(1);
    String reportSubdir =
        String.format(
            "icann/monthly/%d-%02d",
            cursorTimeMinusMonth.getYear(), cursorTimeMinusMonth.getMonthOfYear());
    String filename = getFileName(cursorType, cursorTime, tldStr);
    final BlobId gcsFilename =
        BlobId.of(reportingBucket, String.format("%s/%s", reportSubdir, filename));
    logger.atInfo().log("Reading ICANN report %s from bucket '%s'.", filename, reportingBucket);
    // Check that the report exists
    if (!gcsUtils.existsAndNotEmpty(gcsFilename)) {
      String logMessage =
          String.format(
              "Could not upload %s report for %s because file %s (object %s in bucket %s) did not"
                  + " exist.",
              cursorType, tldStr, filename, gcsFilename.getName(), gcsFilename.getBucket());
      if (clock.nowUtc().dayOfMonth().get() == 1) {
        logger.atInfo().log(logMessage + " This report may not have been staged yet.");
      } else {
        logger.atSevere().log(logMessage);
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

    // Set cursor to first day of next month if the upload succeeded
    if (success) {
      Cursor newCursor =
          Cursor.create(
              cursorType,
              cursorTime.withTimeAtStartOfDay().withDayOfMonth(1).plusMonths(1),
              Registry.get(tldStr));
      // In order to keep the transactions short-lived, we load all of the cursors in a single
      // transaction then later use per-cursor transactions when checking + saving the cursors. We
      // run behind a lock so the cursors shouldn't be changed, but double check to be sure.
      success =
          tm().transact(
                  () -> {
                    Cursor fromDb = tm().transact(() -> tm().loadByEntity(cursor));
                    if (!cursor.equals(fromDb)) {
                      logger.atSevere().log(
                          "Expected previously-loaded cursor %s to equal current cursor %s",
                          cursor, fromDb);
                      return false;
                    }
                    tm().put(newCursor);
                    return true;
                  });
    }
    reportSummaryBuilder.put(filename, success);
  }

  private String getFileName(CursorType cursorType, DateTime cursorTime, String tld) {
    DateTime cursorTimeMinusMonth = cursorTime.withDayOfMonth(1).minusMonths(1);
    return String.format(
        "%s%s%d%02d.csv",
        tld,
        (cursorType.equals(CursorType.ICANN_UPLOAD_ACTIVITY) ? "-activity-" : "-transactions-"),
        cursorTimeMinusMonth.year().get(),
        cursorTimeMinusMonth.monthOfYear().get());
  }

  /** Returns a map of each cursor to the tld. */
  private ImmutableMap<Cursor, String> loadCursors() {

    ImmutableSet<Registry> registries = Registries.getTldEntitiesOfType(TldType.REAL);

    ImmutableMap<VKey<? extends Cursor>, Registry> activityKeyMap =
        loadKeyMap(registries, CursorType.ICANN_UPLOAD_ACTIVITY);
    ImmutableMap<VKey<? extends Cursor>, Registry> transactionKeyMap =
        loadKeyMap(registries, CursorType.ICANN_UPLOAD_TX);

    ImmutableSet.Builder<VKey<? extends Cursor>> keys = new ImmutableSet.Builder<>();
    keys.addAll(activityKeyMap.keySet());
    keys.addAll(transactionKeyMap.keySet());

    Map<VKey<? extends Cursor>, Cursor> cursorMap = tm().loadByKeysIfPresent(keys.build());
    ImmutableMap.Builder<Cursor, String> cursors = new ImmutableMap.Builder<>();
    cursors.putAll(
        defaultNullCursorsToNextMonthAndAddToMap(
            activityKeyMap, CursorType.ICANN_UPLOAD_ACTIVITY, cursorMap));
    cursors.putAll(
        defaultNullCursorsToNextMonthAndAddToMap(
            transactionKeyMap, CursorType.ICANN_UPLOAD_TX, cursorMap));
    return cursors.build();
  }

  private ImmutableMap<VKey<? extends Cursor>, Registry> loadKeyMap(
      ImmutableSet<Registry> registries, CursorType type) {
    return Maps.uniqueIndex(registries, r -> Cursor.createVKey(type, r.getTldStr()));
  }

  /**
   * Return a map with the Cursor and scope for each key in the keyMap. If the key from the keyMap
   * does not have an existing cursor, create a new cursor with a default cursorTime of the first of
   * next month.
   */
  private ImmutableMap<Cursor, String> defaultNullCursorsToNextMonthAndAddToMap(
      Map<VKey<? extends Cursor>, Registry> keyMap,
      CursorType type,
      Map<VKey<? extends Cursor>, Cursor> cursorMap) {
    ImmutableMap.Builder<Cursor, String> cursors = new ImmutableMap.Builder<>();
    keyMap.forEach(
        (key, registry) -> {
          // Cursor time is defaulted to the first of next month since a new tld will not yet have a
          // report staged for upload.
          Cursor cursor =
              cursorMap.getOrDefault(
                  key,
                  Cursor.create(
                      type,
                      clock.nowUtc().withDayOfMonth(1).withTimeAtStartOfDay().plusMonths(1),
                      registry));
          if (!cursorMap.containsValue(cursor)) {
            tm().put(cursor);
          }
          cursors.put(cursor, registry.getTldStr());
        });
    return cursors.build();
  }

  /** Don't retry when reports are already uploaded or can't be uploaded. */
  private static final String ICANN_UPLOAD_PERMANENT_ERROR_MESSAGE =
      "A report for that month already exists, the cut-off date already passed";

  /** Don't retry when the IP address isn't allow-listed, as retries go through the same IP. */
  private static final Pattern ICANN_UPLOAD_ALLOW_LIST_ERROR =
      Pattern.compile("Your IP address .+ is not allowed to connect");

  /** Predicate to retry uploads on IOException, so long as they aren't non-retryable errors. */
  private static boolean isUploadFailureRetryable(Throwable e) {
    return (e instanceof IOException)
        && !e.getMessage().contains(ICANN_UPLOAD_PERMANENT_ERROR_MESSAGE)
        && !ICANN_UPLOAD_ALLOW_LIST_ERROR.matcher(e.getMessage()).matches();
  }

  private void emailUploadResults(ImmutableMap<String, Boolean> reportSummary) {
    if (reportSummary.size() == 0) {
      logger.atInfo().log("No uploads were attempted today; skipping notification email.");
      return;
    }
    String subject =
        String.format(
            "ICANN Monthly report upload summary: %d/%d succeeded",
            // This filter() does in fact do something: It counts only the trues.
            reportSummary.values().stream().filter((b) -> b).count(), reportSummary.size());
    String body =
        String.format(
            "Report Filename - Upload status:\n%s",
            reportSummary.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(
                    (e) ->
                        String.format("%s - %s", e.getKey(), e.getValue() ? "SUCCESS" : "FAILURE"))
                .collect(Collectors.joining("\n")));
    emailService.sendEmail(EmailMessage.create(subject, body, recipient, sender));
  }

  private byte[] readBytesFromGcs(BlobId reportFilename) throws IOException {
    try (InputStream gcsInput = gcsUtils.openInputStream(reportFilename)) {
      return ByteStreams.toByteArray(gcsInput);
    }
  }
}
