// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.UrlConnectionUtils.gUnzipBytes;
import static google.registry.request.UrlConnectionUtils.isGZipped;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.request.auth.Auth;
import google.registry.util.UrlConnectionException;
import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Loads the current list of RDAP Base URLs from the ICANN servers.
 *
 * <p>This will update ALL the REAL registrars. If a REAL registrar doesn't have an RDAP entry in
 * MoSAPI, we'll delete any BaseUrls it has.
 *
 * <p>The ICANN base website that provides this information can be found at <a
 * href=https://www.iana.org/assignments/registrar-ids/registrar-ids.xhtml>here</a>. The provided
 * CSV endpoint requires no authentication.
 */
@Action(
    service = GaeService.BACKEND,
    path = "/_dr/task/updateRegistrarRdapBaseUrls",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public final class UpdateRegistrarRdapBaseUrlsAction implements Runnable {

  private static final String RDAP_IDS_URL =
      "https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject UrlConnectionService urlConnectionService;

  @Inject
  UpdateRegistrarRdapBaseUrlsAction() {}

  @Override
  public void run() {
    try {
      ImmutableMap<String, String> ianaIdsToUrls = getIanaIdsToUrls();
      tm().transact(() -> processAllRegistrars(ianaIdsToUrls));
    } catch (Exception e) {
      throw new InternalServerErrorException("Error when retrieving RDAP base URL CSV file", e);
    }
  }

  private static void processAllRegistrars(ImmutableMap<String, String> ianaIdsToUrls) {
    int nonUpdatedRegistrars = 0;
    for (Registrar registrar : Registrar.loadAll()) {
      // Only update REAL registrars
      if (registrar.getType() != Registrar.Type.REAL) {
        continue;
      }
      String ianaId = String.valueOf(registrar.getIanaIdentifier());
      String baseUrl = ianaIdsToUrls.get(ianaId);
      ImmutableSet<String> baseUrls =
          baseUrl == null ? ImmutableSet.of() : ImmutableSet.of(baseUrl);
      if (registrar.getRdapBaseUrls().equals(baseUrls)) {
        nonUpdatedRegistrars++;
      } else {
        if (baseUrls.isEmpty()) {
          logger.atInfo().log(
              "Removing RDAP base URLs for registrar %s", registrar.getRegistrarId());
        } else {
          logger.atInfo().log(
              "Updating RDAP base URLs for registrar %s from %s to %s",
              registrar.getRegistrarId(), registrar.getRdapBaseUrls(), baseUrls);
        }
        tm().put(registrar.asBuilder().setRdapBaseUrls(baseUrls).build());
      }
    }
    logger.atInfo().log("No change in RDAP base URLs for %d registrars", nonUpdatedRegistrars);
  }

  private ImmutableMap<String, String> getIanaIdsToUrls()
      throws IOException, GeneralSecurityException {
    CSVParser csv;
    HttpURLConnection connection = urlConnectionService.createConnection(new URL(RDAP_IDS_URL));
    // Explictly set the accepted encoding, as we know Brotli causes us problems when talking to
    // ICANN.
    connection.setRequestProperty(ACCEPT_ENCODING, "gzip");
    String csvString;
    try {
      if (connection.getResponseCode() != SC_OK) {
        throw new UrlConnectionException("Failed to load RDAP base URLs from ICANN", connection);
      }
      // With GZIP encoding header in the request (see above) ICANN had still sent response in plain
      // text until at some point they started sending the response encoded in gzip, which broke our
      // parsing of the response. Because of that it was decided to check for the response encoding,
      // just in case they ever start sending a plain text again.
      byte[] responseBytes = UrlConnectionUtils.getResponseBytes(connection);
      csvString =
          new String(isGZipped(responseBytes) ? gUnzipBytes(responseBytes) : responseBytes, UTF_8);
    } finally {
      connection.disconnect();
    }
    csv =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            .parse(new StringReader(csvString));
    ImmutableMap.Builder<String, String> result = new ImmutableMap.Builder<>();
    for (CSVRecord record : csv) {
      String ianaIdentifierString = record.get("ID");
      String rdapBaseUrl = record.get("RDAP Base URL");
      if (!rdapBaseUrl.isEmpty()) {
        result.put(ianaIdentifierString, rdapBaseUrl);
      }
    }
    return result.build();
  }
}
