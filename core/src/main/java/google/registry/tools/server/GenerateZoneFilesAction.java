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

package google.registry.tools.server;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.io.BaseEncoding.base16;
import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.hibernate.CacheMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that requests generation of BIND zone files for a set of TLDs at a given time.
 *
 * <p>Zone files for each requested TLD are written to GCS. TLDs without entries produce zone files
 * with only a header. The export time must be at least two minutes in the past and no more than 29
 * days in the past, and must be at midnight UTC.
 */
@Action(
    service = Action.Service.BACKEND,
    path = GenerateZoneFilesAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class GenerateZoneFilesAction implements Runnable, JsonActionRunner.JsonAction {

  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/task/generateZoneFiles";

  /** Number of domains to process in one batch. */
  private static final int BATCH_SIZE = 1000;

  /** Format for the zone file name. */
  private static final String FILENAME_FORMAT = "%s-%s.zone";

  /** Format for the GCS path to a file. */
  private static final String GCS_PATH_FORMAT = "gs://%s/%s";

  /** Format for the zone file header. */
  private static final String HEADER_FORMAT = "$ORIGIN\t%s.\n\n";

  /** Format for NS records. */
  private static final String NS_FORMAT = "%s\t%d\tIN\tNS\t%s.\n";

  /** Format for DS records. */
  private static final String DS_FORMAT = "%s\t%d\tIN\tDS\t%d %d %d %s\n";

  /** Format for A and AAAA records. */
  private static final String A_FORMAT = "%s\t%d\tIN\t%s\t%s\n";

  @Inject JsonActionRunner jsonActionRunner;
  @Inject @Config("zoneFilesBucket") String bucket;

  @Inject
  @Config("databaseRetention")
  Duration databaseRetention;

  @Inject @Config("dnsDefaultATtl") Duration dnsDefaultATtl;
  @SuppressWarnings("DurationVariableWithUnits") // false-positive Error Prone check
  @Inject @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl;
  @Inject @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl;
  @Inject Clock clock;
  @Inject GcsUtils gcsUtils;

  @Inject GenerateZoneFilesAction() {}

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, Object> handleJsonRequest(Map<String, ?> json) {
    @SuppressWarnings("unchecked")
    ImmutableSet<String> tlds = ImmutableSet.copyOf((List<String>) json.get("tlds"));
    final DateTime exportTime = DateTime.parse(json.get("exportTime").toString());
    // We disallow exporting within the past 2 minutes because there might be outstanding writes.
    // We can only reliably call loadAtPointInTime at times that are UTC midnight and >
    // databaseRetention ago in the past.
    DateTime now = clock.nowUtc();
    if (exportTime.isAfter(now.minusMinutes(2))) {
      throw new BadRequestException("Invalid export time: must be > 2 minutes ago");
    }
    if (exportTime.isBefore(now.minus(databaseRetention))) {
      throw new BadRequestException(
          String.format(
              "Invalid export time: must be < %d days ago", databaseRetention.getStandardDays()));
    }
    tlds.forEach(tld -> generateForTld(tld, exportTime));
    ImmutableList<String> filenames =
        tlds.stream()
            .map(
                tld ->
                    String.format(
                        GCS_PATH_FORMAT, bucket, String.format(FILENAME_FORMAT, tld, exportTime)))
            .collect(toImmutableList());
    return ImmutableMap.of(
        "filenames", filenames);
  }

  private void generateForTld(String tld, DateTime exportTime) {
    ImmutableList<String> stanzas = tm().transact(() -> getStanzasForTld(tld, exportTime));
    BlobId outputBlobId = BlobId.of(bucket, String.format(FILENAME_FORMAT, tld, exportTime));
    try (OutputStream gcsOutput = gcsUtils.openOutputStream(outputBlobId);
        Writer osWriter = new OutputStreamWriter(gcsOutput, UTF_8);
        PrintWriter writer = new PrintWriter(osWriter)) {
      writer.printf(HEADER_FORMAT, tld);
      stanzas.forEach(writer::println);
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ImmutableList<String> getStanzasForTld(String tld, DateTime exportTime) {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    ScrollableResults<Domain> scrollableResults =
        tm().query("FROM Domain WHERE tld = :tld AND deletionTime > :exportTime")
            .setParameter("tld", tld)
            .setParameter("exportTime", exportTime)
            .unwrap(Query.class)
            .setCacheMode(CacheMode.IGNORE)
            .scroll(ScrollMode.FORWARD_ONLY);
    for (int i = 1; scrollableResults.next(); i = (i + 1) % BATCH_SIZE) {
      Domain domain = scrollableResults.get();
      populateStanzasForDomain(domain, exportTime, result);
      if (i == 0) {
        tm().getEntityManager().flush();
        tm().getEntityManager().clear();
      }
    }
    return result.build();
  }

  private void populateStanzasForDomain(
      Domain domain, DateTime exportTime, ImmutableList.Builder<String> result) {
    domain = loadAtPointInTime(domain, exportTime);
    // A null means the domain was deleted (or not created) at this time.
    if (domain == null || !domain.shouldPublishToDns()) {
      return;
    }
    String stanza = domainStanza(domain, exportTime);
    if (!stanza.isEmpty()) {
      result.add(stanza);
    }
    populateStanzasForSubordinateHosts(domain, exportTime, result);
  }

  private void populateStanzasForSubordinateHosts(
      Domain domain, DateTime exportTime, ImmutableList.Builder<String> result) {
    ImmutableSet<String> subordinateHosts = domain.getSubordinateHosts();
    if (!subordinateHosts.isEmpty()) {
      for (Host unprojectedHost : tm().loadByKeys(domain.getNameservers()).values()) {
        Host host = loadAtPointInTime(unprojectedHost, exportTime);
        // A null means the host was deleted (or not created) at this time.
        if (host != null && subordinateHosts.contains(host.getHostName())) {
          String stanza = hostStanza(host, domain.getTld());
          if (!stanza.isEmpty()) {
            result.add(stanza);
          }
        } else if (host == null) {
          log.atSevere().log(
              "Domain %s contained nameserver %s that didn't exist at time %s",
              domain.getRepoId(), unprojectedHost.getRepoId(), exportTime);
        } else {
          log.atSevere().log(
              "Domain %s contained nameserver %s not in subordinate hosts at time %s",
              domain.getRepoId(), unprojectedHost.getRepoId(), exportTime);
        }
      }
    }
  }

  /**
   * Generates DNS records for a domain (NS and DS).
   *
   * <p>For domain foo.tld, these look like this:
   *
   * <pre>
   * {
   *   foo 180 IN NS ns.example.com.
   *   foo 86400 IN DS 1 2 3 000102
   * }
   * </pre>
   */
  private String domainStanza(Domain domain, DateTime exportTime) {
    StringBuilder result = new StringBuilder();
    String domainLabel = stripTld(domain.getDomainName(), domain.getTld());
    Tld tld = Tld.get(domain.getTld());
    for (Host nameserver : tm().loadByKeys(domain.getNameservers()).values()) {
      result.append(
          String.format(
              NS_FORMAT,
              domainLabel,
              tld.getDnsNsTtl().orElse(dnsDefaultNsTtl).getStandardSeconds(),
              // Load the nameservers at the export time in case they've been renamed or deleted.
              loadAtPointInTime(nameserver, exportTime).getHostName()));
    }
    for (DomainDsData dsData : domain.getDsData()) {
      result.append(
          String.format(
              DS_FORMAT,
              domainLabel,
              tld.getDnsDsTtl().orElse(dnsDefaultDsTtl).getStandardSeconds(),
              dsData.getKeyTag(),
              dsData.getAlgorithm(),
              dsData.getDigestType(),
              base16().encode(dsData.getDigest())));
    }
    return result.toString();
  }

  /**
   * Generates DNS records for a domain (A and AAAA).
   *
   * <p>These look like this:
   *
   * <pre>
   * {
   *   ns.foo.tld 3600 IN A 127.0.0.1
   *   ns.foo.tld 3600 IN AAAA 0:0:0:0:0:0:0:1
   * }
   * </pre>
   */
  private String hostStanza(Host host, String tldStr) {
    StringBuilder result = new StringBuilder();
    Tld tld = Tld.get(tldStr);
    for (InetAddress addr : host.getInetAddresses()) {
      // must be either IPv4 or IPv6
      String rrSetClass = (addr instanceof Inet4Address) ? "A" : "AAAA";
      result.append(
          String.format(
              A_FORMAT,
              stripTld(host.getHostName(), tldStr),
              tld.getDnsAPlusAaaaTtl().orElse(dnsDefaultATtl).getStandardSeconds(),
              rrSetClass,
              addr.getHostAddress()));
    }
    return result.toString();
  }

  /**
   * Removes the TLD, if present, from a fully-qualified name.
   *
   * <p>This would not work if a fully qualified host name in a different TLD were passed. But we
   * only generate glue records for in-bailiwick name servers, meaning that the TLD will always
   * match.
   *
   * <p>If, for some unforeseen reason, the TLD is not present, indicate an error condition, so that
   * our process for comparing SQL and DNS data will realize that something is amiss.
   */
  private static String stripTld(String fullyQualifiedName, String tld) {
    return fullyQualifiedName.endsWith(tld)
        ? fullyQualifiedName.substring(0, fullyQualifiedName.length() - tld.length() - 1)
        : (fullyQualifiedName + "***");
  }
}
