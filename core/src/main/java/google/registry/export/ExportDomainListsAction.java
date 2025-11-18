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

import static com.google.common.base.Verify.verifyNotNull;
import static google.registry.model.common.FeatureFlag.FeatureName.INCLUDE_PENDING_DELETE_DATE_FOR_DOMAINS;
import static google.registry.model.tld.Tlds.getTldsOfType;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.storage.BlobId;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.FeatureFlag;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.List;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.TupleTransformer;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 * An action that exports the list of active domains on all real TLDs to Google Drive and GCS.
 *
 * <p>Each TLD's active domain names are exported as a newline-delimited flat text file with the
 * name TLD.txt into the domain-lists bucket. Note that this overwrites the files in place.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/exportDomainLists",
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class ExportDomainListsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SELECT_DOMAINS_STATEMENT =
      "SELECT domainName FROM Domain WHERE tld = :tld AND deletionTime > :now ORDER by domainName";
  private static final String SELECT_DOMAINS_AND_DELETION_TIMES_STATEMENT =
      """
      SELECT d.domain_name, d.deletion_time, d.statuses, gp.type FROM "Domain" d
        LEFT JOIN (SELECT type, domain_repo_id FROM "GracePeriod"
          WHERE type = 'REDEMPTION'
          AND expiration_time > CAST(:now AS timestamptz)) AS gp
        ON d.repo_id = gp.domain_repo_id
        WHERE d.tld = :tld
        AND d.deletion_time > CAST(:now AS timestamptz)
        ORDER BY d.domain_name
      """;

  // This may be a CSV, but it is uses a .txt file extension for back-compatibility
  static final String REGISTERED_DOMAINS_FILENAME_FORMAT = "registered_domains_%s.txt";

  @Inject Clock clock;
  @Inject DriveConnection driveConnection;
  @Inject GcsUtils gcsUtils;

  @Inject @Config("domainListsGcsBucket") String gcsBucket;
  @Inject ExportDomainListsAction() {}

  @Override
  public void run() {
    ImmutableSet<String> realTlds = getTldsOfType(TldType.REAL);
    logger.atInfo().log("Exporting domain lists for TLDs %s.", realTlds);

    boolean includeDeletionTimes =
        tm().transact(() -> FeatureFlag.isActiveNow(INCLUDE_PENDING_DELETE_DATE_FOR_DOMAINS));
    realTlds.forEach(
        tld -> {
          List<String> domainsList =
              replicaTm()
                  .transact(
                      TRANSACTION_REPEATABLE_READ,
                      () -> {
                        if (includeDeletionTimes) {
                          // We want to include deletion times, but only for domains in the 5-day
                          // PENDING_DELETE period after the REDEMPTION grace period. In order to
                          // accomplish this without loading the entire list of domains, we use a
                          // native query to join against the GracePeriod table to find
                          // PENDING_DELETE domains that don't have a REDEMPTION grace period.
                          return replicaTm()
                              .getEntityManager()
                              .createNativeQuery(SELECT_DOMAINS_AND_DELETION_TIMES_STATEMENT)
                              .unwrap(NativeQuery.class)
                              .setTupleTransformer(new DomainResultTransformer())
                              .setParameter("tld", tld)
                              .setParameter("now", replicaTm().getTransactionTime().toString())
                              .getResultList();
                        } else {
                          return replicaTm()
                              .query(SELECT_DOMAINS_STATEMENT, String.class)
                              .setParameter("tld", tld)
                              .setParameter("now", replicaTm().getTransactionTime())
                              .getResultList();
                        }
                      });
          logger.atInfo().log(
              "Exporting %d domains for TLD %s to GCS and Drive.", domainsList.size(), tld);
          String domainsListOutput = Joiner.on('\n').join(domainsList);
          exportToGcs(tld, domainsListOutput, gcsBucket, gcsUtils);
          exportToDrive(tld, domainsListOutput, driveConnection);
        });
  }

  protected static void exportToDrive(
      String tldStr, String domains, DriveConnection driveConnection) {
    verifyNotNull(driveConnection, "Expecting non-null driveConnection");
    try {
      Tld tld = Tld.get(tldStr);
      if (tld.getDriveFolderId() == null) {
        logger.atInfo().log(
            "Skipping registered domains export for TLD %s because Drive folder isn't specified.",
            tldStr);
      } else {
        String resultMsg =
            driveConnection.createOrUpdateFile(
                String.format(REGISTERED_DOMAINS_FILENAME_FORMAT, tldStr),
                MediaType.PLAIN_TEXT_UTF_8,
                tld.getDriveFolderId(),
                domains.getBytes(UTF_8));
        logger.atInfo().log(
            "Exporting registered domains succeeded for TLD %s, response was: %s",
            tldStr, resultMsg);
      }
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to Drive, skipping...", tldStr);
    }
  }

  protected static void exportToGcs(
      String tld, String domains, String gcsBucket, GcsUtils gcsUtils) {
    BlobId blobId = BlobId.of(gcsBucket, tld + ".txt");
    try (OutputStream gcsOutput = gcsUtils.openOutputStream(blobId);
        Writer osWriter = new OutputStreamWriter(gcsOutput, UTF_8)) {
      osWriter.write(domains);
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to GCS, skipping...", tld);
    }
  }

  /** Transforms the multiple columns selected from SQL into the output line. */
  private static class DomainResultTransformer implements TupleTransformer<String> {
    @Override
    public String transformTuple(Object[] domainResult, String[] strings) {
      String domainName = (String) domainResult[0];
      Instant deletionInstant = (Instant) domainResult[1];
      DateTime deletionTime = new DateTime(deletionInstant.toEpochMilli(), DateTimeZone.UTC);
      String[] domainStatuses = (String[]) domainResult[2];
      String gracePeriodType = (String) domainResult[3];
      boolean inPendingDelete =
          ImmutableSet.copyOf(domainStatuses).contains(StatusValue.PENDING_DELETE.toString())
              && !GracePeriodStatus.REDEMPTION.toString().equals(gracePeriodType);
      return String.format("%s,%s", domainName, inPendingDelete ? deletionTime : "");
    }
  }
}
