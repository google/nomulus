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

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.common.base.Verify.verify;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.jcraft.jsch.ChannelSftp.OVERWRITE;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD_SFTP;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static java.util.Arrays.asList;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpProgressMonitor;
import dagger.Lazy;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registry.Registry;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.rde.JSchSshSession.JSchSshSessionFactory;
import google.registry.request.Action;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import google.registry.util.TeeOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
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
    service = Action.Service.BACKEND,
    path = RdeUploadAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class RdeUploadAction implements Runnable, EscrowTask {

  static final String PATH = "/_dr/task/rdeUpload";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject GcsUtils gcsUtils;
  @Inject EscrowTaskRunner runner;

  // Using Lazy<JSch> instead of JSch to prevent fetching of rdeSsh*Keys before we know we're
  // actually going to use them. See b/37868282
  //
  // This prevents making an unnecessary time-expensive (and potentially failing) API call to the
  // external KMS system when the RdeUploadAction ends up not being used (if the EscrowTaskRunner
  // determins this EscrowTask was already completed today).
  @Inject Lazy<JSch> lazyJsch;

  @Inject JSchSshSessionFactory jschSshSessionFactory;
  @Inject Response response;
  @Inject SftpProgressMonitor sftpProgressMonitor;
  @Inject TaskQueueUtils taskQueueUtils;
  @Inject Retrier retrier;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Config("rdeBucket") String bucket;
  @Inject @Config("rdeInterval") Duration interval;
  @Inject @Config("rdeUploadLockTimeout") Duration timeout;
  @Inject @Config("rdeUploadSftpCooldown") Duration sftpCooldown;
  @Inject @Config("rdeUploadUrl") URI uploadUrl;
  @Inject @Key("rdeReceiverKey") PGPPublicKey receiverKey;
  @Inject @Key("rdeSigningKey") PGPKeyPair signingKey;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject @Named("rde-report") Queue reportQueue;
  @Inject RdeUploadAction() {}

  @Override
  public void run() {
    logger.atInfo().log("Attempting to acquire RDE upload lock for TLD '%s'.", tld);
    runner.lockRunAndRollForward(this, Registry.get(tld), timeout, CursorType.RDE_UPLOAD, interval);
    taskQueueUtils.enqueue(
        reportQueue, withUrl(RdeReportAction.PATH).param(RequestParameters.PARAM_TLD, tld));
  }

  @Override
  public void runWithLock(final DateTime watermark) throws Exception {
    logger.atInfo().log("Verifying readiness to upload the RDE deposit.");
    Optional<Cursor> cursor =
        transactIfJpaTm(() -> tm().loadByKeyIfPresent(Cursor.createVKey(RDE_STAGING, tld)));
    DateTime stagingCursorTime = getCursorTimeOrStartOfTime(cursor);
    if (isBeforeOrAt(stagingCursorTime, watermark)) {
      throw new NoContentException(
          String.format(
              "Waiting on RdeStagingAction for TLD %s to send %s upload; "
                  + "last RDE staging completion was at %s",
              tld, watermark, stagingCursorTime));
    }
    DateTime sftpCursorTime =
        transactIfJpaTm(() -> tm().loadByKeyIfPresent(Cursor.createVKey(RDE_UPLOAD_SFTP, tld)))
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
    int revision = RdeRevision.getNextRevision(tld, watermark, FULL) - 1;
    verify(revision >= 0, "RdeRevision was not set on generated deposit");
    final String name = RdeNamingUtils.makeRydeFilename(tld, watermark, FULL, 1, revision);
    final GcsFilename xmlFilename = new GcsFilename(bucket, name + ".xml.ghostryde");
    final GcsFilename xmlLengthFilename = new GcsFilename(bucket, name + ".xml.length");
    GcsFilename reportFilename = new GcsFilename(bucket, name + "-report.xml.ghostryde");
    verifyFileExists(xmlFilename);
    verifyFileExists(xmlLengthFilename);
    verifyFileExists(reportFilename);
    logger.atInfo().log("Commencing RDE upload for TLD '%s' to '%s'.", tld, uploadUrl);
    final long xmlLength = readXmlLength(xmlLengthFilename);
    retrier.callWithRetry(
        () -> upload(xmlFilename, xmlLength, watermark, name), JSchException.class);
    logger.atInfo().log(
        "Updating RDE cursor '%s' for TLD '%s' following successful upload.", RDE_UPLOAD_SFTP, tld);
    tm().transact(
            () ->
                tm().put(
                        Cursor.create(
                            RDE_UPLOAD_SFTP, tm().getTransactionTime(), Registry.get(tld))));
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK %s %s\n", tld, watermark));
  }

  /**
   * Performs a blocking upload of a cloud storage XML file to escrow provider, converting
   * it to the RyDE format along the way by applying tar+compress+encrypt+sign, and saving the
   * created RyDE file on GCS for future reference.
   *
   * <p>This is done by layering a bunch of {@link java.io.FilterOutputStream FilterOutputStreams}
   * on top of each other in reverse order that turn XML bytes into a RyDE file while
   * simultaneously uploading it to the SFTP endpoint, and then using {@link ByteStreams#copy} to
   * blocking-copy bytes from the cloud storage {@code InputStream} to the RyDE/SFTP pipeline.
   *
   * <p>In pseudo-shell, the whole process looks like the following:
   *
   * <pre>   {@code
   *   gcs read $xmlFile \                                   # Get GhostRyDE from cloud storage.
   *     | decrypt | decompress \                            # Convert it to XML.
   *     | tar | file | compress | encrypt | sign /tmp/sig \ # Convert it to a RyDE file.
   *     | tee gs://bucket/$rydeFilename.ryde \              # Save a copy of the RyDE file to GCS.
   *     | sftp put $dstUrl/$rydeFilename.ryde \             # Upload to SFTP server.
   *    && sftp put $dstUrl/$rydeFilename.sig </tmp/sig \    # Upload detached signature.
   *    && cat /tmp/sig > gs://bucket/$rydeFilename.sig      # Save a copy of signature to GCS.
   *   }</pre>
   */
  @VisibleForTesting
  protected void upload(GcsFilename xmlFile, long xmlLength, DateTime watermark, String name)
      throws Exception {
    logger.atInfo().log("Uploading XML file '%s' to remote path '%s'.", xmlFile, uploadUrl);
    try (InputStream gcsInput = gcsUtils.openInputStream(xmlFile);
        InputStream ghostrydeDecoder = Ghostryde.decoder(gcsInput, stagingDecryptionKey)) {
      try (JSchSshSession session = jschSshSessionFactory.create(lazyJsch.get(), uploadUrl);
          JSchSftpChannel ftpChan = session.openSftpChannel()) {
        ByteArrayOutputStream sigOut = new ByteArrayOutputStream();
        String rydeFilename = name + ".ryde";
        GcsFilename rydeGcsFilename = new GcsFilename(bucket, rydeFilename);
        try (OutputStream ftpOutput =
                ftpChan.get().put(rydeFilename, sftpProgressMonitor, OVERWRITE);
            OutputStream gcsOutput = gcsUtils.openOutputStream(rydeGcsFilename);
            TeeOutputStream teeOutput = new TeeOutputStream(asList(ftpOutput, gcsOutput));
            RydeEncoder rydeEncoder =
                new RydeEncoder.Builder()
                    .setRydeOutput(teeOutput, receiverKey)
                    .setSignatureOutput(sigOut, signingKey)
                    .setFileMetadata(name, xmlLength, watermark)
                    .build()) {
            long bytesCopied = ByteStreams.copy(ghostrydeDecoder, rydeEncoder);
            logger.atInfo().log("uploaded %,d bytes: %s", bytesCopied, rydeFilename);
          }
        String sigFilename = name + ".sig";
        byte[] signature = sigOut.toByteArray();
        gcsUtils.createFromBytes(new GcsFilename(bucket, sigFilename), signature);
        ftpChan.get().put(new ByteArrayInputStream(signature), sigFilename);
        logger.atInfo().log("uploaded %,d bytes: %s", signature.length, sigFilename);
      }
    }
  }

  /** Reads the contents of a file from Cloud Storage that contains nothing but an integer. */
  private long readXmlLength(GcsFilename xmlLengthFilename) throws IOException {
    try (InputStream input = gcsUtils.openInputStream(xmlLengthFilename)) {
      return Ghostryde.readLength(input);
    }
  }

  private void verifyFileExists(GcsFilename filename) {
    verify(gcsUtils.existsAndNotEmpty(filename), "Missing file: %s", filename);
  }
}
