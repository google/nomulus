// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.tld.Tlds.getTldsOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import jakarta.persistence.Query;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

/**
 * Exports to Drive lists of domains that are in the PENDING_DELETE stage but not deleted.
 *
 * <p>This includes domains that are in the regular 35-day redemption + pending-delete period plus
 * any not-yet-expired domains for which an explicit DOMAIN_DELETE action has been issued.
 *
 * <p>We provide these lists in an effort to reduce spam requests intended for drop-catching.
 */
@Action(
    service = Action.GaeService.BACKEND,
    path = "/_dr/task/exportPendingDeleteDomains",
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class ExportPendingDeleteDomainsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String FIND_PENDING_DELETE_DOMAINS_QUERY =
      "SELECT * FROM \"Domain\" WHERE tld = :tld AND 'PENDING_DELETE' = any(statuses) AND"
          + " deletion_time > CAST(:now AS timestamptz) ORDER BY deletion_time";

  static final String PENDING_DELETE_DOMAINS_FILENAME = "pending_delete_domains.csv";

  private final DriveConnection driveConnection;

  @Inject
  public ExportPendingDeleteDomainsAction(DriveConnection driveConnection) {
    this.driveConnection = driveConnection;
  }

  @Override
  public void run() {
    ImmutableSet<String> realTlds = getTldsOfType(Tld.TldType.REAL);
    logger.atInfo().log("Exporting pending-delete domains for TLDs %s.", realTlds);
    realTlds.forEach(this::runForTld);
  }

  private void runForTld(String tldStr) {
    replicaTm().transact(() -> runInReplicaTransaction(tldStr));
  }

  private void runInReplicaTransaction(String tldStr) {
    Tld tld = Tld.get(tldStr);
    String driveFolderId = Tld.get(tldStr).getDriveFolderId();
    if (isNullOrEmpty(driveFolderId)) {
      logger.atInfo().log(
          "Skipping pending-delete domains export for TLD %s because Drive folder isn't specified.",
          tldStr);
      return;
    }
    Query query =
        replicaTm()
            .getEntityManager()
            .createNativeQuery(FIND_PENDING_DELETE_DOMAINS_QUERY, Domain.class)
            .setParameter("now", replicaTm().getTransactionTime().toString())
            .setParameter("tld", tldStr);
    Stream<Domain> resultsStream = (Stream<Domain>) query.getResultStream();
    String outputString =
        resultsStream
            .map(d -> String.format("%s,%s", d.getDomainName(), d.getDeletionTime()))
            .collect(Collectors.joining("\n"));
    try {
      String resultMsg =
          driveConnection.createOrUpdateFile(
              PENDING_DELETE_DOMAINS_FILENAME,
              MediaType.CSV_UTF_8,
              tld.getDriveFolderId(),
              outputString.getBytes(UTF_8));
      logger.atInfo().log(
          "Exporting pending-delete domains succeeded for TLD %s, response was: %s",
          tldStr, resultMsg);
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log(
          "Error exporting pending-delete domains for TLD %s to Drive. Skipping...", tldStr);
    }
  }
}
