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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.reporting.icann.IcannReportingModule.PARAM_SUBDIR;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registries;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.schema.cursor.CursorDao;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
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
    Callable<Void> lockRunner =
        () -> {
          ImmutableMap.Builder<String, Boolean> reportSummaryBuilder = new ImmutableMap.Builder<>();

          ImmutableMap<Cursor, CursorInfo> cursors = loadCursors();

          // If cursor time is before now, upload the corresponding report
          cursors.entrySet().stream()
              .filter(entry -> getCursorTimeOrStartOfTime(entry.getKey()).isBefore(clock.nowUtc()))
              .forEach(
                  entry -> {
                    DateTime cursorTime = getCursorTimeOrStartOfTime(entry.getKey());
                    uploadReport(
                        cursorTime,
                        entry.getValue().getType(),
                        entry.getValue().getTld(),
                        reportSummaryBuilder);
                  });
          // Send email of which reports were uploaded
          emailUploadResults(reportSummaryBuilder.build());
          response.setStatus(SC_OK);
          response.setContentType(PLAIN_TEXT_UTF_8);
          return null;
        };

    String lockname = "IcannReportingUploadAction";
    if (!lockHandler.executeWithLocks(lockRunner, null, Duration.standardHours(2), lockname)) {
      throw new ServiceUnavailableException("Lock for IcannReportingUploadAction already in use");
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
      String logMessage =
          String.format(
              "Could not upload %s report for %s because file %s did not exist.",
              cursorType, tldStr, filename);
      if (clock.nowUtc().dayOfMonth().get() == 1) {
        logger.atInfo().withCause(e).log(logMessage + " This report may not have been staged yet.");
      } else {
        logger.atSevere().withCause(e).log(logMessage);
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
      logger.atWarning().withCause(e).log("Upload to %s failed", gcsFilename);
    }
    reportSummaryBuilder.put(filename, success);

    // Set cursor to first day of next month if the upload succeeded
    if (success) {
      Cursor newCursor =
          Cursor.create(
              cursorType,
              cursorTime.withTimeAtStartOfDay().withDayOfMonth(1).plusMonths(1),
              Registry.get(tldStr));
      CursorDao.saveCursor(
          newCursor,
          Optional.ofNullable(tldStr).orElse(google.registry.schema.cursor.Cursor.GLOBAL));
    }
  }

  private String getFileName(CursorType cursorType, DateTime cursorTime, String tld) {
    return String.format(
        "%s%s%d%02d.csv",
        tld,
        (cursorType.equals(CursorType.ICANN_UPLOAD_ACTIVITY) ? "-activity-" : "-transactions-"),
        cursorTime.year().get(),
        cursorTime.withDayOfMonth(1).minusMonths(1).monthOfYear().get());
  }

  /** Returns a map of each cursor to the CursorType and tld. */
  private ImmutableMap<Cursor, CursorInfo> loadCursors() {

    ImmutableSet<Registry> registries = Registries.getTldEntitiesOfType(TldType.REAL);

    Map<Key<Cursor>, Registry> activityKeyMap =
        loadKeyMap(registries, CursorType.ICANN_UPLOAD_ACTIVITY);
    Map<Key<Cursor>, Registry> transactionKeyMap =
        loadKeyMap(registries, CursorType.ICANN_UPLOAD_TX);

    ImmutableSet.Builder<Key<Cursor>> keys = new ImmutableSet.Builder<>();
    keys.addAll(activityKeyMap.keySet());
    keys.addAll(transactionKeyMap.keySet());

    Map<Key<Cursor>, Cursor> cursorMap = ofy().load().keys(keys.build());
    ImmutableMap.Builder<Cursor, CursorInfo> cursors = new ImmutableMap.Builder<>();
    defaultNullCursorsToNextMonthAndAddToMap(
        activityKeyMap, CursorType.ICANN_UPLOAD_ACTIVITY, cursorMap, cursors);
    defaultNullCursorsToNextMonthAndAddToMap(
        transactionKeyMap, CursorType.ICANN_UPLOAD_TX, cursorMap, cursors);
    return cursors.build();
  }

  private Map<Key<Cursor>, Registry> loadKeyMap(
      ImmutableSet<Registry> registries, CursorType type) {
    return registries.stream().collect(toImmutableMap(r -> Cursor.createKey(type, r), r -> r));
  }

  /**
   * Populate the cursors map with the Cursor and CursorInfo for each key in the keyMap. If the key
   * from the keyMap does not have an existing cursor, create a new cursor with a default cursorTime
   * of the first of next month.
   */
  private void defaultNullCursorsToNextMonthAndAddToMap(
      Map<Key<Cursor>, Registry> keyMap,
      CursorType type,
      Map<Key<Cursor>, Cursor> cursorMap,
      ImmutableMap.Builder<Cursor, CursorInfo> cursors) {
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
            tm().transact(() -> ofy().save().entity(cursor));
          }
          cursors.put(cursor, CursorInfo.create(type, registry.getTldStr()));
        });
  }

  /** Don't retry when reports are already uploaded or can't be uploaded. */
  private static final String ICANN_UPLOAD_PERMANENT_ERROR_MESSAGE =
      "A report for that month already exists, the cut-off date already passed";

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

  @AutoValue
  abstract static class CursorInfo {
    static CursorInfo create(CursorType type, @Nullable String tld) {
      return new AutoValue_IcannReportingUploadAction_CursorInfo(type, tld);
    }

    public abstract CursorType getType();

    @Nullable
    abstract String getTld();
  }
}
