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

package google.registry.export;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.SortedSet;

/** Action that exports the premium terms list for a TLD to Google Drive. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/exportPremiumTerms",
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class ExportPremiumTermsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final MediaType EXPORT_MIME_TYPE = MediaType.PLAIN_TEXT_UTF_8;
  static final String TLD_IDENTIFIER_FORMAT = "# TLD: %s";
  static final String PREMIUM_TERMS_FILENAME_FORMAT = "CONFIDENTIAL_premium_terms_%s.txt";

  @Inject DriveConnection driveConnection;

  @Inject
  @Config("premiumTermsExportDisclaimer")
  String exportDisclaimer;

  @Inject
  @Parameter(RequestParameters.PARAM_TLD)
  String tldStr;

  @Inject Response response;

  @Inject
  ExportPremiumTermsAction() {}

  /**
   * Exports the premium terms for the TLD specified via the "tld" param to a file in the Google
   * Drive folder configured for that TLD.
   *
   * <p>The export file is named "CONFIDENTIAL_premium_terms.txt" and is encoded in UTF-8. It begins
   * with the disclaimer text that is immediately followed by premium terms, each occupying a line.
   * The file ends with a trailing newline.
   *
   * <p>Each term is formatted as "term,price", where price is the ISO-4217 three-letter currency
   * code followed by a space and then the numeric amount. For example:
   *
   * <pre>
   * bank,USD 1599.00
   * </pre>
   *
   * <p>This servlet prints the ID of the file in GoogleDrive that was created/updated.
   */
  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);
    try {
      Tld tld = Tld.get(tldStr);
      String resultMsg = checkConfig(tld).orElseGet(() -> exportPremiumTerms(tld));
      response.setStatus(SC_OK);
      response.setPayload(resultMsg);
    } catch (Throwable e) {
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(e.getMessage());
      throw new RuntimeException(
          String.format("Exception occurred while exporting premium terms for TLD %s.", tldStr), e);
    }
  }

  /**
   * Checks if {@link Tld} is properly configured to export premium terms.
   *
   * @return {@link Optional#empty()} if {@link Tld} export may proceed. Otherwise returns an error
   *     message
   */
  private Optional<String> checkConfig(Tld tld) {
    if (isNullOrEmpty(tld.getDriveFolderId())) {
      logger.atInfo().log(
          "Skipping premium terms export for TLD %s because Drive folder isn't specified.", tldStr);
      return Optional.of("Skipping export because no Drive folder is associated with this TLD");
    }
    if (tld.getPremiumListName().isEmpty()) {
      logger.atInfo().log("No premium terms to export for TLD '%s'.", tldStr);
      return Optional.of("No premium lists configured");
    }
    return Optional.empty();
  }

  private String exportPremiumTerms(Tld tld) {
    try {
      String fileId =
          driveConnection.createOrUpdateFile(
              String.format(PREMIUM_TERMS_FILENAME_FORMAT, tldStr),
              EXPORT_MIME_TYPE,
              tld.getDriveFolderId(),
              getFormattedPremiumTerms(tld).getBytes(UTF_8));
      logger.atInfo().log(
          "Exporting premium terms succeeded for TLD %s, file ID is: %s", tldStr, fileId);
      return fileId;
    } catch (IOException e) {
      throw new RuntimeException("Error exporting premium terms file to Drive.", e);
    }
  }

  private String getFormattedPremiumTerms(Tld tld) {
    checkState(tld.getPremiumListName().isPresent(), "%s does not have a premium list", tldStr);
    String premiumListName = tld.getPremiumListName().get();
    checkState(
        PremiumListDao.getLatestRevision(premiumListName).isPresent(),
        "Could not load premium list for " + tldStr);
    SortedSet<String> premiumTerms =
        PremiumListDao.loadAllPremiumEntries(premiumListName).stream()
            .map(PremiumEntry::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(String::compareTo));

    String tldIdentifier = String.format(TLD_IDENTIFIER_FORMAT, tldStr);
    Iterable<String> commentsAndTerms =
        Iterables.concat(ImmutableList.of(exportDisclaimer.trim(), tldIdentifier), premiumTerms);
    return Joiner.on("\n").join(commentsAndTerms) + "\n";
  }
}
