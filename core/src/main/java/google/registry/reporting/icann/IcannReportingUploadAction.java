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
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.reporting.icann.IcannReportingModule.MANIFEST_FILE_NAME;
import static google.registry.reporting.icann.IcannReportingModule.PARAM_SUBDIR;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;

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

  @Inject
  @Parameter(RequestParameters.PARAM_TLD)
  String tld;

  @Inject
  IcannReportingUploadAction() {}

  @Override
  public void run() {
    String reportBucketname = String.format("%s/%s", reportingBucket, subdir);
    ImmutableList<String> manifestedFiles = getManifestedFiles(reportBucketname);
    List<String> filesToUpload = new ArrayList<>();
    for (String file : manifestedFiles) {
      if (file.contains(tld)) {
        filesToUpload.add(file);
      }
    }
    ImmutableMap.Builder<String, Boolean> reportSummaryBuilder = new ImmutableMap.Builder<>();
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.ICANN_UPLOAD, Registry.get(tld))).now();
    if (cursor != null && cursor.getCursorTime().isAfter(clock.nowUtc())) {
      return;
    }
    boolean allUploadsSucceeded = true;
    // Report on all manifested files
    for (String reportFilename : filesToUpload) {
      logger.atInfo().log(
          "Reading ICANN report %s from bucket %s", reportFilename, reportBucketname);
      final GcsFilename gcsFilename = new GcsFilename(reportBucketname, reportFilename);
      verifyFileExists(gcsFilename);
      boolean success = false;
      try {
        success =
            retrier.callWithRetry(
                () -> {
                  final byte[] payload = readBytesFromGcs(gcsFilename);
                  return icannReporter.send(payload, reportFilename);
                },
                IcannReportingUploadAction::isUploadFailureRetryable);
      } catch (RuntimeException e) {
        logger.atWarning().withCause(e).log("Upload to %s failed.", gcsFilename);
      }
      reportSummaryBuilder.put(reportFilename, success);
      if (!success) {
        allUploadsSucceeded = false;
      }
    }

    // Check Manifest cursor and upload if necessary
    Cursor manifestCursor =
        ofy().load().key(Cursor.createGlobalKey(CursorType.ICANN_UPLOAD_MANIFEST)).now();
    if (manifestCursor == null || manifestCursor.getCursorTime().isBefore(clock.nowUtc())) {
      reportSummaryBuilder.put(MANIFEST_FILE_NAME, uploadManifest());
    }
    emailUploadResults(reportSummaryBuilder.build());
    response.setStatus(SC_OK);
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK, attempted uploading %d reports", filesToUpload.size()));
    if (allUploadsSucceeded) {
      tm().transact(
              () ->
                  ofy()
                      .save()
                      .entity(
                          Cursor.create(
                              CursorType.ICANN_UPLOAD,
                              clock.nowUtc().withTimeAtStartOfDay().plusMonths(1).withDayOfMonth(1),
                              Registry.get(tld))));
    }
  }

  private boolean uploadManifest() {
    String reportBucketname = String.format("%s/%s", reportingBucket, subdir);
    logger.atInfo().log("Reading MANIFEST file");
    final GcsFilename filename = new GcsFilename(reportBucketname, MANIFEST_FILE_NAME);
    verifyFileExists(filename);
    boolean success = false;
    try {
      success =
          retrier.callWithRetry(
              () -> {
                final byte[] payload = readBytesFromGcs(filename);
                return icannReporter.send(payload, MANIFEST_FILE_NAME);
              },
              IcannReportingUploadAction::isUploadFailureRetryable);
    } catch (RuntimeException e) {
      logger.atWarning().withCause(e).log("Upload to %s failed.", filename);
    }
    if (success) {
      tm().transact(
              () ->
                  ofy()
                      .save()
                      .entity(
                          Cursor.createGlobal(
                              CursorType.ICANN_UPLOAD_MANIFEST,
                              clock
                                  .nowUtc()
                                  .withTimeAtStartOfDay()
                                  .plusMonths(1)
                                  .withDayOfMonth(1))));
    }
    return success;
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

  private ImmutableList<String> getManifestedFiles(String reportBucketname) {
    GcsFilename manifestFilename = new GcsFilename(reportBucketname, MANIFEST_FILE_NAME);
    verifyFileExists(manifestFilename);
    return retrier.callWithRetry(
        () ->
            ImmutableList.copyOf(
                Splitter.on('\n')
                    .omitEmptyStrings()
                    .split(new String(readBytesFromGcs(manifestFilename), UTF_8))),
        IOException.class);
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
