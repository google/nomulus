// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.model.tld.label.ReservedList.loadReservedLists;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.cloud.storage.BlobId;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.ReservedList;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Daily action that uploads unavailable domain names on applicable TLDs to BSA.
 *
 * <p>The upload is a single zipped text file containing combined details for all BSA-enrolled TLDs.
 * The text is a newline-delimited list of punycoded fully qualified domain names, and contains all
 * domains on each TLD that are registered and/or reserved.
 *
 * <p>The file is also uploaded to GCS to preserve it as a record for ourselves.
 */
@Action(
    service = Service.BSA,
    path = "/_dr/task/uploadBsaUnavailableNames",
    method = POST,
    auth = Auth.AUTH_API_ADMIN)
public class UploadBsaUnavailableDomains implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  Clock clock;

  GcsUtils gcsUtils;

  String gcsBucket;

  @Inject
  public UploadBsaUnavailableDomains(
      Clock clock, GcsUtils gcsUtils, @Config("bsaUnavailableDomainsGcsBucket") String gcsBucket) {
    this.clock = clock;
    this.gcsUtils = gcsUtils;
    this.gcsBucket = gcsBucket;
  }

  @Override
  public void run() {
    DateTime runTime = clock.nowUtc();
    String unavailableDomains = Joiner.on("\n").join(getUnavailableDomains(runTime));
    uploadToGcs(unavailableDomains, runTime);
    uploadToBsa(unavailableDomains, runTime);
  }

  /** Uploads the unavailable domains list to GCS in the unavailable domains bucket. */
  void uploadToGcs(String unavailableDomains, DateTime runTime) {
    BlobId blobId = BlobId.of(gcsBucket, runTime.toString() + ".txt");
    try (OutputStream gcsOutput = gcsUtils.openOutputStream(blobId);
        Writer osWriter = new OutputStreamWriter(gcsOutput, US_ASCII)) {
      osWriter.write(unavailableDomains);
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Error writing BSA unavailable domains to GCS; skipping to BSA upload ...");
    }
  }

  void uploadToBsa(String unavailableDomains, DateTime runTime) {}

  private ImmutableSortedSet<String> getUnavailableDomains(DateTime runTime) {
    return replicaTm()
        .transact(
            () -> {
              ImmutableSet<Tld> bsaEnabledTlds =
                  getTldEntitiesOfType(TldType.REAL).stream()
                      .filter(tld -> isEnrolledWithBsa(tld, runTime))
                      .collect(toImmutableSet());

              ImmutableSortedSet.Builder<String> unavailableDomains =
                  new ImmutableSortedSet.Builder<>(Ordering.natural());
              for (Tld tld : bsaEnabledTlds) {
                for (ReservedList reservedList : loadReservedLists(tld.getReservedListNames())) {
                  if (reservedList.getShouldPublish()) {
                    unavailableDomains.addAll(
                        reservedList.getReservedListEntries().keySet().stream()
                            .map(label -> toDomain(label, tld))
                            .collect(toImmutableSet()));
                  }
                }
              }

              unavailableDomains.addAll(
                  replicaTm()
                      .query(
                          "SELECT domainName FROM Domain "
                              + "WHERE tld IN :tlds "
                              + "AND deletionTime > :now ",
                          String.class)
                      .setParameter(
                          "tlds",
                          bsaEnabledTlds.stream().map(Tld::getTldStr).collect(toImmutableSet()))
                      .setParameter("now", runTime)
                      .getResultList());
              return unavailableDomains.build();
            });
  }

  private static String toDomain(String domainLabel, Tld tld) {
    return String.format("%s.%s", domainLabel, tld.getTldStr());
  }
}
