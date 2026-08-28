// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.ExpiryAccessPeriodMode;
import google.registry.model.tld.Tld.TldType;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/** An action that exports the upcoming domain drop list across all open TLDs to Google Drive. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/exportDropList",
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class ExportDropListAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String DROPLIST_FILENAME = "domain_drop_list.csv";
  static final ImmutableList<String> CSV_HEADER =
      ImmutableList.of("domain_name", "tld", "deletion_time");
  private static final CSVFormat CSV_FORMAT =
      CSVFormat.DEFAULT.builder().setRecordSeparator('\n').get();

  private static final String SELECT_UPCOMING_DELETIONS_STATEMENT =
      """
      SELECT domainName, tld, deletionTime FROM Domain
      WHERE tld IN :xapTlds
      AND deletionTime > :now
      AND deletionTime < :endOfTime
      ORDER BY domainName
      """;

  @Inject Clock clock;
  @Inject DriveConnection driveConnection;

  @Inject
  @Config("domainDropListDriveFolderId")
  Optional<String> driveFolderId;

  @Inject
  ExportDropListAction() {}

  @Override
  public void run() {
    if (driveFolderId.isEmpty() || driveFolderId.get().isEmpty()) {
      logger.atInfo().log("Skipping domain drop list export because Drive folder isn't specified.");
      return;
    }

    Instant now = clock.now();
    ImmutableSet<String> xapTlds =
        getTldEntitiesOfType(TldType.REAL).stream()
            .filter(Tld::isInvoicingEnabled)
            .filter(tld -> tld.getExpiryAccessPeriodModeAt(now) == ExpiryAccessPeriodMode.ENABLED)
            .map(Tld::getTldStr)
            .collect(toImmutableSet());
    logger.atInfo().log("Exporting domain drop list for open TLDs with XAP enabled: %s", xapTlds);

    if (xapTlds.isEmpty()) {
      logger.atInfo().log("No open TLDs found with XAP enabled.");
      exportToDrive(createCsv(ImmutableList.of()));
      return;
    }

    // Any database transaction failures will throw an unchecked PersistenceException, causing
    // RequestHandler to set HTTP 500 so Cloud Tasks can retry the task on retryable-cron-tasks.
    List<Object[]> queryResults =
        replicaTm()
            .transact(
                TRANSACTION_REPEATABLE_READ,
                () ->
                    replicaTm()
                        .query(SELECT_UPCOMING_DELETIONS_STATEMENT, Object[].class)
                        .setParameter("xapTlds", xapTlds)
                        .setParameter("now", now)
                        .setParameter("endOfTime", END_INSTANT)
                        .getResultList());

    exportToDrive(createCsv(queryResults));
  }

  private static String createCsv(List<Object[]> queryResults) {
    StringWriter stringWriter = new StringWriter();
    try (CSVPrinter printer = new CSVPrinter(stringWriter, CSV_FORMAT)) {
      printer.printRecord(CSV_HEADER);
      for (Object[] row : queryResults) {
        printer.printRecord(row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create drop list CSV", e);
    }
    return stringWriter.toString();
  }

  private void exportToDrive(String csvContent) {
    verifyNotNull(driveConnection, "Expecting non-null driveConnection");
    try {
      String resultMsg =
          driveConnection.createOrUpdateFile(
              DROPLIST_FILENAME,
              MediaType.CSV_UTF_8,
              driveFolderId.get(),
              csvContent.getBytes(UTF_8));
      logger.atInfo().log("Exporting domain drop list succeeded, response was: %s", resultMsg);
    } catch (IOException e) {
      // Rethrow as an unchecked exception so RequestHandler sets HTTP 500, causing Cloud Tasks to
      // retry this action on the retryable-cron-tasks queue upon transient Drive failures.
      throw new RuntimeException(
          String.format("Error exporting domain drop list to Drive folder %s", driveFolderId.get()),
          e);
    }
  }
}
