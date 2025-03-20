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

package google.registry.export;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import jakarta.inject.Inject;

/** Action that exports the publicly viewable reserved terms list for a TLD to Google Drive. */
@Action(
    service = GaeService.BACKEND,
    path = "/_dr/task/exportReservedTerms",
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class ExportReservedTermsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final MediaType EXPORT_MIME_TYPE = MediaType.PLAIN_TEXT_UTF_8;
  static final String RESERVED_TERMS_FILENAME_FORMAT = "reserved_terms_%s.txt";

  @Inject DriveConnection driveConnection;
  @Inject ExportUtils exportUtils;

  @Inject
  @Parameter(RequestParameters.PARAM_TLD)
  String tldStr;

  @Inject Response response;
  @Inject ExportReservedTermsAction() {}

  /**
   * Exports the reserved terms for the TLD specified via the "tld" param to a newline-delimited
   * UTF-8-formatted CSV file (with one column) named "reserved_terms.txt" in the Google Drive
   * folder with the id specified for that TLD.
   *
   * <p>This servlet prints the ID of the file in GoogleDrive that was created/updated.
   */
  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);
    try {
      Tld tld = Tld.get(tldStr);
      String resultMsg;
      if (tld.getReservedListNames().isEmpty() && isNullOrEmpty(tld.getDriveFolderId())) {
        resultMsg = "No reserved lists configured";
        logger.atInfo().log("No reserved terms to export for TLD '%s'.", tldStr);
      } else if (tld.getDriveFolderId() == null) {
        resultMsg = "Skipping export because no Drive folder is associated with this TLD";
        logger.atInfo().log(
            "Skipping reserved terms export for TLD %s because Drive folder isn't specified.",
            tldStr);
      } else {
        resultMsg =
            driveConnection.createOrUpdateFile(
                String.format(RESERVED_TERMS_FILENAME_FORMAT, tldStr),
                EXPORT_MIME_TYPE,
                tld.getDriveFolderId(),
                exportUtils.exportReservedTerms(tld).getBytes(UTF_8));
        logger.atInfo().log(
            "Exporting reserved terms succeeded for TLD %s, response was: %s", tldStr, resultMsg);
      }
      response.setStatus(SC_OK);
      response.setPayload(resultMsg);
    } catch (Throwable e) {
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(e.getMessage());
      throw new RuntimeException(
          String.format("Exception occurred while exporting reserved terms for TLD %s.", tldStr),
          e);
    }
  }
}
