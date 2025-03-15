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

package google.registry.bsa;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getLast;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.model.tld.label.ReservedList.loadReservedLists;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.cloud.storage.BlobId;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import google.registry.bsa.api.BsaCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.ReservedList;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
    service = GaeService.BSA,
    path = "/_dr/task/uploadBsaUnavailableNames",
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public class UploadBsaUnavailableDomainsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int BATCH_SIZE = 40000;

  Clock clock;

  BsaCredential bsaCredential;

  GcsUtils gcsUtils;

  String gcsBucket;

  String apiUrl;
  BsaEmailSender emailSender;

  google.registry.request.Response response;

  @Inject
  public UploadBsaUnavailableDomainsAction(
      Clock clock,
      BsaCredential bsaCredential,
      GcsUtils gcsUtils,
      BsaEmailSender emailSender,
      @Config("bsaUnavailableDomainsGcsBucket") String gcsBucket,
      @Config("bsaUploadUnavailableDomainsUrl") String apiUrl,
      google.registry.request.Response response) {
    this.clock = clock;
    this.bsaCredential = bsaCredential;
    this.gcsUtils = gcsUtils;
    this.gcsBucket = gcsBucket;
    this.apiUrl = apiUrl;
    this.emailSender = emailSender;
    this.response = response;
  }

  @Override
  public void run() {
    // TODO(mcilwain): Implement a date Cursor, have the cronjob run frequently, and short-circuit
    //                 the run if the daily upload is already completed.
    DateTime runTime = clock.nowUtc();
    String unavailableDomains = Joiner.on("\n").join(getUnavailableDomains(runTime));
    if (unavailableDomains.isEmpty()) {
      logger.atWarning().log("No unavailable domains found; terminating.");
      emailSender.sendNotification(
          "BSA daily upload found no domains to upload", "This is unexpected. Please investigate.");
    } else {
      boolean isGcsSuccess = uploadToGcs(unavailableDomains, runTime);
      boolean isBsaSuccess = uploadToBsa(unavailableDomains, runTime);
      if (isBsaSuccess && isGcsSuccess) {
        emailSender.sendNotification("BSA daily upload completed successfully", "");
      } else {
        emailSender.sendNotification(
            "BSA daily upload completed with errors", "Please see logs for details.");
      }
    }
  }

  /** Uploads the unavailable domains list to GCS in the unavailable domains bucket. */
  boolean uploadToGcs(String unavailableDomains, DateTime runTime) {
    logger.atInfo().log("Uploading unavailable names file to GCS in bucket %s", gcsBucket);
    BlobId blobId = BlobId.of(gcsBucket, createFilename(runTime));
    try (OutputStream gcsOutput = gcsUtils.openOutputStream(blobId);
        Writer osWriter = new OutputStreamWriter(gcsOutput, US_ASCII)) {
      osWriter.write(unavailableDomains);
      return true;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Error writing BSA unavailable domains to GCS; skipping to BSA upload ...");
      return false;
    }
  }

  boolean uploadToBsa(String unavailableDomains, DateTime runTime) {
    try {
      byte[] gzippedContents = gzipUnavailableDomains(unavailableDomains);
      String sha512Hash = ByteSource.wrap(gzippedContents).hash(Hashing.sha512()).toString();
      String filename = createFilename(runTime);
      OkHttpClient client = new OkHttpClient().newBuilder().build();

      RequestBody body =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart(
                  "zone",
                  null,
                  RequestBody.create(
                      String.format("{\"checkSum\": \"%s\"}", sha512Hash).getBytes(US_ASCII),
                      MediaType.parse("application/json")))
              .addFormDataPart(
                  "file",
                  String.format("%s.gz", filename),
                  RequestBody.create(gzippedContents, MediaType.parse("application/octet-stream")))
              .build();

      Request request =
          new Request.Builder()
              .url(apiUrl)
              .method("POST", body)
              .addHeader("Authorization", "Bearer " + bsaCredential.getAuthToken())
              .build();

      logger.atInfo().log(
          "Uploading unavailable domains list %s to %s with hash %s", filename, apiUrl, sha512Hash);
      try (Response uploadResponse = client.newCall(request).execute()) {
        logger.atInfo().log(
            "Received response with code %s from server: %s",
            uploadResponse.code(),
            uploadResponse.body() == null ? "(none)" : uploadResponse.body().string());
      }
      return true;
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while attempting to upload to BSA, aborting.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload("Error while attempting to upload to BSA: " + e.getMessage());
      return false;
    }
  }

  private byte[] gzipUnavailableDomains(String unavailableDomains) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
      try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        gzipOutputStream.write(unavailableDomains.getBytes(US_ASCII));
      }
      return byteArrayOutputStream.toByteArray();
    }
  }

  private static String createFilename(DateTime runTime) {
    return String.format("unavailable_domains_%s.txt", runTime.toString());
  }

  private ImmutableSortedSet<String> getUnavailableDomains(DateTime runTime) {
    // Get list of TLDs to process.
    ImmutableSet<Tld> bsaEnabledTlds =
        getTldEntitiesOfType(TldType.REAL).stream()
            .filter(tld -> isEnrolledWithBsa(tld, runTime))
            .collect(toImmutableSet());

    logger.atInfo().log(
        "Getting unavailable domains in TLDs: %s ...",
        bsaEnabledTlds.stream().map(Tld::getTldStr).collect(toImmutableSet()));

    ImmutableSortedSet.Builder<String> unavailableDomains =
        new ImmutableSortedSet.Builder<>(Ordering.natural());

    // Add domains on reserved lists to unavailable names list.
    replicaTm()
        .transact(
            () -> {
              for (Tld tld : bsaEnabledTlds) {
                for (ReservedList reservedList : loadReservedLists(tld.getReservedListNames())) {
                  unavailableDomains.addAll(
                      reservedList.getReservedListEntries().keySet().stream()
                          .map(label -> toDomain(label, tld))
                          .collect(toImmutableSet()));
                }
              }
            });

    // Add existing domains to unavailable names list, in batches so as to not time out on replica.
    ImmutableSet<String> tldNames =
        bsaEnabledTlds.stream().map(Tld::getTldStr).collect(toImmutableSet());
    ImmutableList<String> domainsBatch;
    Optional<String> lastDomain = Optional.empty();
    do {
      final Optional<String> lastDomainCopy = lastDomain;
      domainsBatch =
          replicaTm()
              .transact(
                  () -> {
                    String sql =
                        String.format(
                            "SELECT domainName FROM Domain "
                                + "WHERE tld IN :tlds "
                                + "AND deletionTime > :now "
                                + "%s ORDER BY domainName ASC",
                            lastDomainCopy.isPresent()
                                ? "AND domainName > :lastInPreviousBatch"
                                : "");
                    TypedQuery<String> query =
                        replicaTm()
                            .query(sql, String.class)
                            .setParameter("tlds", tldNames)
                            .setParameter("now", runTime);
                    lastDomainCopy.ifPresent(l -> query.setParameter("lastInPreviousBatch", l));
                    return query
                        .setMaxResults(BATCH_SIZE)
                        .getResultStream()
                        .collect(toImmutableList());
                  });
      unavailableDomains.addAll(domainsBatch);
      lastDomain = Optional.ofNullable(domainsBatch.isEmpty() ? null : getLast(domainsBatch));
    } while (domainsBatch.size() == BATCH_SIZE);

    ImmutableSortedSet<String> result = unavailableDomains.build();
    logger.atInfo().log("Found %d total unavailable domains.", result.size());
    return result;
  }

  private static String toDomain(String domainLabel, Tld tld) {
    return String.format("%s.%s", domainLabel, tld.getTldStr());
  }
}
