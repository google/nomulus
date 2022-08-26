// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.io.StringReader;
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
 * <p>The ICANN base website that provides this information can be found at
 * https://www.iana.org/assignments/registrar-ids/registrar-ids.xhtml. The provided CSV endpoint
 * requires no authentication.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/updateRegistrarRdapBaseUrls",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class UpdateRegistrarRdapBaseUrlsAction implements Runnable {

  private static final GenericUrl RDAP_IDS_URL =
      new GenericUrl("https://www.iana.org/assignments/registrar-ids/registrar-ids-1.csv");
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject HttpTransport httpTransport;

  @Inject
  UpdateRegistrarRdapBaseUrlsAction() {}

  @Override
  public void run() {
    ImmutableMap<String, String> ianaIdsToUrls = getIanaIdsToUrls();
    tm().transact(() -> processAllRegistrars(ianaIdsToUrls));
  }

  private void processAllRegistrars(ImmutableMap<String, String> ianaIdsToUrls) {
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

  private ImmutableMap<String, String> getIanaIdsToUrls() {
    CSVParser csv;
    try {
      HttpRequest request = httpTransport.createRequestFactory().buildGetRequest(RDAP_IDS_URL);
      // AppEngine might insert accept-encodings for us if we use the default gzip, so remove it
      request.getHeaders().setAcceptEncoding(null);
      HttpResponse response = request.execute();
      String csvString = new String(ByteStreams.toByteArray(response.getContent()), UTF_8);
      csv =
          CSVFormat.Builder.create(CSVFormat.DEFAULT)
              .setHeader()
              .setSkipHeaderRecord(true)
              .build()
              .parse(new StringReader(csvString));
    } catch (IOException e) {
      throw new RuntimeException("Error when retrieving RDAP base URL CSV file", e);
    }
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
