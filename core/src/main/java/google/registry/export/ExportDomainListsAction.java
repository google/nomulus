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
import static google.registry.model.tld.Registries.getTldsOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
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
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.Clock;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import javax.inject.Inject;

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
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ExportDomainListsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String REGISTERED_DOMAINS_FILENAME = "registered_domains.txt";

  @Inject Clock clock;
  @Inject DriveConnection driveConnection;
  @Inject GcsUtils gcsUtils;

  @Inject @Config("domainListsGcsBucket") String gcsBucket;
  @Inject ExportDomainListsAction() {}

  @Override
  public void run() {
    ImmutableSet<String> realTlds = getTldsOfType(TldType.REAL);
    logger.atInfo().log("Exporting domain lists for TLDs %s.", realTlds);
    realTlds.forEach(
        tld -> {
          List<String> domains =
              tm().transact(
                      () ->
                          // Note that if we had "creationTime <= :now" in the condition (not
                          // necessary as there is no pending creation, the order of deletionTime
                          // and creationTime in the query would have been significant and it
                          // should come after deletionTime. When Hibernate substitutes "now" it
                          // will first validate that the **first** field that is to be compared
                          // with it (deletionTime) is assignable from the substituted Java object
                          // (click.nowUtc()). Since creationTime is a CreateAutoTimestamp, if it
                          // comes first, we will need to substitute "now" with
                          // CreateAutoTimestamp.create(clock.nowUtc()). This might look a bit
                          // strange as the Java object type is clearly incompatible between the
                          // two fields deletionTime (DateTime) and creationTime, yet they are
                          // compared with the same "now". It is actually OK because in the end
                          // Hibernate converts everything to SQL types (and Java field names to
                          // SQL column names) to run the query. Both CreateAutoTimestamp and
                          // DateTime are persisted as timestamp_z in SQL. It is only the
                          // validation that compares the Java types, and only with the first
                          // field that compares with the substituted value.
                          jpaTm()
                              .query(
                                  "SELECT fullyQualifiedDomainName FROM Domain "
                                      + "WHERE tld = :tld "
                                      + "AND deletionTime > :now "
                                      + "ORDER by fullyQualifiedDomainName ASC",
                                  String.class)
                              .setParameter("tld", tld)
                              .setParameter("now", clock.nowUtc())
                              .getResultList());
          String domainsList = Joiner.on("\n").join(domains);
          logger.atInfo().log(
              "Exporting %d domains for TLD %s to GCS and Drive.", domains.size(), tld);
          exportToGcs(tld, domainsList, gcsBucket, gcsUtils);
          exportToDrive(tld, domainsList, driveConnection);
        });
  }

  protected static boolean exportToDrive(
      String tld, String domains, DriveConnection driveConnection) {
    verifyNotNull(driveConnection, "Expecting non-null driveConnection");
    try {
      Registry registry = Registry.get(tld);
      if (registry.getDriveFolderId() == null) {
        logger.atInfo().log(
            "Skipping registered domains export for TLD %s because Drive folder isn't specified.",
            tld);
      } else {
        String resultMsg =
            driveConnection.createOrUpdateFile(
                REGISTERED_DOMAINS_FILENAME,
                MediaType.PLAIN_TEXT_UTF_8,
                registry.getDriveFolderId(),
                domains.getBytes(UTF_8));
        logger.atInfo().log(
            "Exporting registered domains succeeded for TLD %s, response was: %s", tld, resultMsg);
      }
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to Drive, skipping...", tld);
      return false;
    }
    return true;
  }

  protected static boolean exportToGcs(
      String tld, String domains, String gcsBucket, GcsUtils gcsUtils) {
    BlobId blobId = BlobId.of(gcsBucket, tld + ".txt");
    try (OutputStream gcsOutput = gcsUtils.openOutputStream(blobId);
        Writer osWriter = new OutputStreamWriter(gcsOutput, UTF_8)) {
      osWriter.write(domains);
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error exporting registered domains for TLD %s to GCS, skipping...", tld);
      return false;
    }
    return true;
  }
}
