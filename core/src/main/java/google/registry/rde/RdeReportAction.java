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
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.request.Action.Method.POST;
import static google.registry.schema.cursor.CursorDao.loadAndCompare;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.registry.Registry;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.request.Action;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.io.InputStream;
import javax.inject.Inject;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that uploads a small XML RDE report to ICANN after {@link RdeUploadAction} has finished.
 */
@Action(
    service = Action.Service.BACKEND,
    path = RdeReportAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class RdeReportAction implements Runnable, EscrowTask {

  static final String PATH = "/_dr/task/rdeReport";

  @Inject GcsUtils gcsUtils;
  @Inject EscrowTaskRunner runner;
  @Inject Response response;
  @Inject RdeReporter reporter;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Config("rdeBucket") String bucket;
  @Inject @Config("rdeInterval") Duration interval;
  @Inject @Config("rdeReportLockTimeout") Duration timeout;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject RdeReportAction() {}

  @Override
  public void run() {
    runner.lockRunAndRollForward(this, Registry.get(tld), timeout, CursorType.RDE_REPORT, interval);
  }

  @Override
  public void runWithLock(DateTime watermark) throws Exception {
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.RDE_UPLOAD, Registry.get(tld))).now();
    loadAndCompare(cursor, tld);
    DateTime cursorTime = getCursorTimeOrStartOfTime(cursor);
    if (isBeforeOrAt(cursorTime, watermark)) {
      throw new NoContentException(
          String.format(
              "Waiting on RdeUploadAction for TLD %s to send %s report; "
                  + "last upload completion was at %s",
              tld, watermark, cursorTime));
    }
    String prefix = RdeNamingUtils.makeRydeFilename(tld, watermark, FULL, 1, 0);
    GcsFilename reportFilename = new GcsFilename(bucket, prefix + "-report.xml.ghostryde");
    verify(gcsUtils.existsAndNotEmpty(reportFilename), "Missing file: %s", reportFilename);
    reporter.send(readReportFromGcs(reportFilename));
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK %s %s\n", tld, watermark));
  }

  /** Reads and decrypts the XML file from cloud storage. */
  private byte[] readReportFromGcs(GcsFilename reportFilename) throws IOException, PGPException {
    try (InputStream gcsInput = gcsUtils.openInputStream(reportFilename);
        InputStream ghostrydeDecoder = Ghostryde.decoder(gcsInput, stagingDecryptionKey)) {
      return ByteStreams.toByteArray(ghostrydeDecoder);
    }
  }
}
