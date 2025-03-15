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

package google.registry.rde;

import static com.google.common.base.Verify.verify;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.jcraft.jsch.ChannelSftp.OVERWRITE;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD_SFTP;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeModule.RDE_REPORT_QUEUE;
import static google.registry.rde.RdeUtils.findMostRecentPrefixForWatermark;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static java.util.Arrays.asList;

import com.google.cloud.storage.BlobId;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpProgressMonitor;
import dagger.Lazy;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Tld;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.rde.JSchSshSession.JSchSshSessionFactory;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.TeeOutputStream;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that securely uploads an RDE XML file from Cloud Storage to a trusted third party (such as
 * Iron Mountain) via SFTP.
 *
 * <p>This action is invoked by {@link RdeStagingAction} once it's created the files we need. The
 * date is calculated from {@link CursorType#RDE_UPLOAD}.
 *
 * <p>Once this action completes, it rolls the cursor forward a day and triggers {@link
 * RdeReportAction}.
 */
@Action(
    service = GaeService.BACKEND,
    path = RdeUploadAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public final class RdeUploadAction implements Runnable, EscrowTask {

  public static final String PATH = "/_dr/task/rdeUpload";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject GcsUtils gcsUtils;
  @Inject EscrowTaskRunner runner;

  // Using Lazy<JSch> instead of JSch to prevent fetching of rdeSsh*Keys before we know we're
  // actually going to use them. See b/37868282
  //
  // This prevents making an unnecessary time-expensive (and potentially failing) API call to the
  // external KMS system when the RdeUploadAction ends up not being used (if the EscrowTaskRunner
  // determines this EscrowTask was already completed today).
  @Inject Lazy<JSch> lazyJsch;

  @Inject JSchSshSessionFactory jschSshSessionFactory;
  @Inject Response response;
  @Inject SftpProgressMonitor sftpProgressMonitor;
  @Inject CloudTasksUtils cloudTasksUtils;
  @Inject Retrier retrier;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Parameter(RdeModule.PARAM_PREFIX) Optional<String> prefix;
  @Inject @Config("rdeBucket") String bucket;
  @Inject @Config("rdeInterval") Duration interval;
  @Inject @Config("rdeUploadLockTimeout") Duration timeout;
  @Inject @Config("rdeUploadSftpCooldown") Duration sftpCooldown;
  @Inject @Config("rdeUploadUrl") URI uploadUrl;
  @Inject @Key("rdeReceiverKey") PGPPublicKey receiverKey;
  @Inject @Key("rdeSigningKey") PGPKeyPair signingKey;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject RdeUploadAction() {}
  @Override
  public void run() {
    logger.atInfo().log("Attempting to acquire RDE upload lock for TLD '%s'.", tld);
    runner.lockRunAndRollForward(this, Tld.get(tld), timeout, CursorType.RDE_UPLOAD, interval);
    HashMultimap<String, String> params = HashMultimap.create();
    params.put(RequestParameters.PARAM_TLD, tld);
    prefix.ifPresent(s -> params.put(RdeModule.PARAM_PREFIX, s));
    cloudTasksUtils.enqueue(
        RDE_REPORT_QUEUE, cloudTasksUtils.createTask(RdeReportAction.class, POST, params));
  }

  @Override
  public void runWithLock(final DateTime watermark) throws Exception {
    // If a prefix is not provided,try to determine the prefix. This should only happen when the RDE
    // upload cron job runs to catch up any un-retried (i. e. expected) RDE failures.
    if (prefix.isEmpty()) {
      prefix = Optional.of(findMostRecentPrefixForWatermark(watermark, bucket, tld, gcsUtils));
    }
    logger.atInfo().log("Verifying readiness to upload the RDE deposit.");
    Optional<Cursor> cursor =
        tm().transact(
                () -> tm().loadByKeyIfPresent(Cursor.createScopedVKey(RDE_STAGING, Tld.get(tld))));
    DateTime stagingCursorTime = getCursorTimeOrStartOfTime(cursor);
    if (isBeforeOrAt(stagingCursorTime, watermark)) {
      throw new NoContentException(
          String.format(
              "Waiting on RdeStagingAction for TLD %s to send %s upload; "
                  + "last RDE staging completion was before %s",
              tld, watermark, stagingCursorTime));
    }
    DateTime sftpCursorTime =
        tm().transact(
                () ->
                    tm().loadByKeyIfPresent(Cursor.createScopedVKey(RDE_UPLOAD_SFTP, Tld.get(tld))))
            .map(Cursor::getCursorTime)
            .orElse(START_OF_TIME);
    Duration timeSinceLastSftp = new Duration(sftpCursorTime, clock.nowUtc());
    if (timeSinceLastSftp.isShorterThan(sftpCooldown)) {
      throw new NoContentException(
          String.format(
              "Waiting on %d minute SFTP cooldown for TLD %s to send %s upload; "
                  + "last upload attempt was at %s (%d minutes ago)",
              sftpCooldown.getStandardMinutes(),
              tld,
              watermark,
              sftpCursorTime,
              timeSinceLastSftp.getStandardMinutes()));
    }
    int revision =
        RdeRevision.getCurrentRevision(tld, watermark, FULL)
            .orElseThrow(
                () -> new IllegalStateException("RdeRevision was not set on generated deposit"));
    final String nameWithoutPrefix =
        RdeNamingUtils.makeRydeFilename(tld, watermark, FULL, 1, revision);
    final String name = prefix.get() + nameWithoutPrefix;
    final BlobId xmlFilename = BlobId.of(bucket, name + ".xml.ghostryde");
    final BlobId xmlLengthFilename = BlobId.of(bucket, name + ".xml.length");
    BlobId reportFilename = BlobId.of(bucket, name + "-report.xml.ghostryde");
    verifyFileExists(xmlFilename);
    verifyFileExists(xmlLengthFilename);
    verifyFileExists(reportFilename);
    logger.atInfo().log("Commencing RDE upload for TLD '%s' to '%s'.", tld, uploadUrl);
    final long xmlLength = readXmlLength(xmlLengthFilename);
    retrier.callWithRetry(
        () -> upload(xmlFilename, xmlLength, watermark, name, nameWithoutPrefix),
        JSchException.class);
    logger.atInfo().log(
        "Updating RDE cursor '%s' for TLD '%s' following successful upload.", RDE_UPLOAD_SFTP, tld);
    tm().transact(
            () ->
                tm().put(
                        Cursor.createScoped(
                            RDE_UPLOAD_SFTP, tm().getTransactionTime(), Tld.get(tld))));
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK %s %s\n", tld, watermark));
  }

  /**
   * Performs a blocking upload of a cloud storage XML file to escrow provider, converting it to the
   * RyDE format along the way by applying tar+compress+encrypt+sign, and saving the created RyDE
   * file on GCS for future reference.
   *
   * <p>This is done by layering a bunch of {@link java.io.FilterOutputStream FilterOutputStreams}
   * on top of each other in reverse order that turn XML bytes into a RyDE file while simultaneously
   * uploading it to the SFTP endpoint, and then using {@link ByteStreams#copy} to blocking-copy
   * bytes from the cloud storage {@code InputStream} to the RyDE/SFTP pipeline.
   *
   * <p>In pseudo-shell, the whole process looks like the following:
   *
   * <pre>{@code
   * gcs read $xmlFile \                                   # Get GhostRyDE from cloud storage.
   *   | decrypt | decompress \                            # Convert it to XML.
   *   | tar | file | compress | encrypt | sign /tmp/sig \ # Convert it to a RyDE file.
   *   | tee gs://bucket/$rydeFilename.ryde \              # Save a copy of the RyDE file to GCS.
   *   | sftp put $dstUrl/$rydeFilename.ryde \             # Upload to SFTP server.
   *  && sftp put $dstUrl/$rydeFilename.sig </tmp/sig \    # Upload detached signature.
   *  && cat /tmp/sig > gs://bucket/$rydeFilename.sig      # Save a copy of signature to GCS.
   *
   * }</pre>
   */
  @VisibleForTesting
  private void upload(
      BlobId xmlFile, long xmlLength, DateTime watermark, String name, String nameWithoutPrefix)
      throws Exception {
    logger.atInfo().log("Uploading XML file '%s' to remote path '%s'.", xmlFile, uploadUrl);
    try (InputStream gcsInput = gcsUtils.openInputStream(xmlFile);
        InputStream ghostrydeDecoder = Ghostryde.decoder(gcsInput, stagingDecryptionKey)) {
      try (JSchSshSession session = jschSshSessionFactory.create(lazyJsch.get(), uploadUrl);
          JSchSftpChannel ftpChan = session.openSftpChannel()) {
        ByteArrayOutputStream sigOut = new ByteArrayOutputStream();
        String rydeFilename = nameWithoutPrefix + ".ryde";
        BlobId rydeGcsFilename = BlobId.of(bucket, name + ".ryde");
        try (OutputStream ftpOutput =
                ftpChan.get().put(rydeFilename, sftpProgressMonitor, OVERWRITE);
            OutputStream gcsOutput = gcsUtils.openOutputStream(rydeGcsFilename);
            TeeOutputStream teeOutput = new TeeOutputStream(asList(ftpOutput, gcsOutput));
            RydeEncoder rydeEncoder =
                new RydeEncoder.Builder()
                    .setRydeOutput(teeOutput, receiverKey)
                    .setSignatureOutput(sigOut, signingKey)
                    .setFileMetadata(nameWithoutPrefix, xmlLength, watermark)
                    .build()) {
          long bytesCopied = ByteStreams.copy(ghostrydeDecoder, rydeEncoder);
          logger.atInfo().log("Uploaded %,d bytes to path '%s'.", bytesCopied, rydeFilename);
        }
        String sigFilename = nameWithoutPrefix + ".sig";
        BlobId sigGcsFilename = BlobId.of(bucket, name + ".sig");
        byte[] signature = sigOut.toByteArray();
        gcsUtils.createFromBytes(sigGcsFilename, signature);
        ftpChan.get().put(new ByteArrayInputStream(signature), sigFilename);
        logger.atInfo().log("Uploaded %,d bytes to path '%s'.", signature.length, sigFilename);
      }
    }
  }

  /** Reads the contents of a file from Cloud Storage that contains nothing but an integer. */
  private long readXmlLength(BlobId xmlLengthFilename) throws IOException {
    try (InputStream input = gcsUtils.openInputStream(xmlLengthFilename)) {
      return Ghostryde.readLength(input);
    }
  }

  private void verifyFileExists(BlobId filename) {
    verify(gcsUtils.existsAndNotEmpty(filename), "Missing file: %s", filename);
  }
}
