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

package google.registry.dns.writer.clouddns;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.util.DomainNameUtils.getSecondLevelDomain;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.Change;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.BaseDnsWriter;
import google.registry.dns.writer.DnsWriter;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registries;
import google.registry.util.Clock;
import google.registry.util.Concurrent;
import google.registry.util.Retrier;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Duration;

/**
 * {@link DnsWriter} implementation that talks to Google Cloud DNS.
 *
 * @see <a href="https://cloud.google.com/dns/docs/">Google Cloud DNS Documentation</a>
 */
public class CloudDnsWriter extends BaseDnsWriter {

  /**
   * The name of the dns writer, as used in {@code Registry.dnsWriter}. Remember to change the value
   * on affected Registry objects to prevent runtime failures.
   */
  public static final String NAME = "CloudDnsWriter";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final ImmutableSet<String> RETRYABLE_EXCEPTION_REASONS =
      ImmutableSet.of("preconditionFailed", "notFound", "alreadyExists");

  private final Clock clock;
  private final RateLimiter rateLimiter;
  private final int numThreads;
  // TODO(shikhman): This uses @Named("transientFailureRetries") which may not be tuned for this
  // application.
  private final Retrier retrier;
  private final Duration defaultATtl;
  private final Duration defaultNsTtl;
  private final Duration defaultDsTtl;
  private final String projectId;
  private final String zoneName;
  private final Dns dnsConnection;
  private final HashMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords = new HashMap<>();

  @Inject
  CloudDnsWriter(
      Dns dnsConnection,
      @Config("projectId") String projectId,
      @DnsWriterZone String zoneName,
      @Config("dnsDefaultATtl") Duration defaultATtl,
      @Config("dnsDefaultNsTtl") Duration defaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration defaultDsTtl,
      @Named("cloudDns") RateLimiter rateLimiter,
      @Named("cloudDnsNumThreads") int numThreads,
      Clock clock,
      Retrier retrier) {
    this.dnsConnection = dnsConnection;
    this.projectId = projectId;
    this.zoneName = zoneName.replace('.', '-');
    this.defaultATtl = defaultATtl;
    this.defaultNsTtl = defaultNsTtl;
    this.defaultDsTtl = defaultDsTtl;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.retrier = retrier;
    this.numThreads = numThreads;
  }

  /** Publish the domain and all subordinate hosts. */
  @Override
  public void publishDomain(String domainName) {
    // Canonicalize name
    String absoluteDomainName = getAbsoluteHostName(domainName);

    // Load the target domain. Note that it can be absent if this domain was just deleted.
    Optional<DomainBase> domainBase =
        loadByForeignKey(DomainBase.class, domainName, clock.nowUtc());

    // Return early if no DNS records should be published.
    // desiredRecordsBuilder is populated with an empty set to indicate that all existing records
    // should be deleted.
    if (!domainBase.isPresent() || !domainBase.get().shouldPublishToDns()) {
      desiredRecords.put(absoluteDomainName, ImmutableSet.of());
      return;
    }

    ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

    // Construct DS records (if any).
    Set<DelegationSignerData> dsData = domainBase.get().getDsData();
    if (!dsData.isEmpty()) {
      HashSet<String> dsRrData = new HashSet<>();
      for (DelegationSignerData ds : dsData) {
        dsRrData.add(ds.toRrData());
      }

      if (!dsRrData.isEmpty()) {
        domainRecords.add(
            new ResourceRecordSet()
                .setName(absoluteDomainName)
                .setTtl((int) defaultDsTtl.getStandardSeconds())
                .setType("DS")
                .setKind("dns#resourceRecordSet")
                .setRrdatas(ImmutableList.copyOf(dsRrData)));
      }
    }

    // Construct NS records (if any).
    Set<String> nameserverData = domainBase.get().loadNameserverHostNames();
    Set<String> subordinateHosts = domainBase.get().getSubordinateHosts();
    if (!nameserverData.isEmpty()) {
      HashSet<String> nsRrData = new HashSet<>();
      for (String hostName : nameserverData) {
        nsRrData.add(getAbsoluteHostName(hostName));

        // Construct glue records for subordinate NS hostnames (if any)
        if (subordinateHosts.contains(hostName)) {
          publishSubordinateHost(hostName);
        }
      }

      if (!nsRrData.isEmpty()) {
        domainRecords.add(
            new ResourceRecordSet()
                .setName(absoluteDomainName)
                .setTtl((int) defaultNsTtl.getStandardSeconds())
                .setType("NS")
                .setKind("dns#resourceRecordSet")
                .setRrdatas(ImmutableList.copyOf(nsRrData)));
      }
    }

    desiredRecords.put(absoluteDomainName, domainRecords.build());
    logger.atFine().log(
        "Will write %d records for domain %s", domainRecords.build().size(), absoluteDomainName);
  }

  private void publishSubordinateHost(String hostName) {
    logger.atInfo().log("Publishing glue records for %s", hostName);
    // Canonicalize name
    String absoluteHostName = getAbsoluteHostName(hostName);

    // Load the target host. Note that it can be absent if this host was just deleted.
    // desiredRecords is populated with an empty set to indicate that all existing records
    // should be deleted.
    Optional<HostResource> host = loadByForeignKey(HostResource.class, hostName, clock.nowUtc());

    // Return early if the host is deleted.
    if (!host.isPresent()) {
      desiredRecords.put(absoluteHostName, ImmutableSet.of());
      return;
    }

    ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

    // Construct A and AAAA records (if any).
    HashSet<String> aRrData = new HashSet<>();
    HashSet<String> aaaaRrData = new HashSet<>();
    for (InetAddress ip : host.get().getInetAddresses()) {
      if (ip instanceof Inet4Address) {
        aRrData.add(ip.getHostAddress());
      } else {
        checkArgument(ip instanceof Inet6Address);
        aaaaRrData.add(ip.getHostAddress());
      }
    }

    if (!aRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultATtl.getStandardSeconds())
              .setType("A")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aRrData)));
    }

    if (!aaaaRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultATtl.getStandardSeconds())
              .setType("AAAA")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aaaaRrData)));
    }

    desiredRecords.put(absoluteHostName, domainRecords.build());
  }

  /**
   * Publish A/AAAA records to Cloud DNS.
   *
   * <p>Cloud DNS has no API for glue -- A/AAAA records are automatically matched to their
   * corresponding NS records to serve glue.
   */
  @Override
  public void publishHost(String hostName) {
    // Get the superordinate domain name of the host.
    InternetDomainName host = InternetDomainName.from(hostName);
    Optional<InternetDomainName> tld = Registries.findTldForName(host);

    // Host not managed by our registry, no need to update DNS.
    if (!tld.isPresent()) {
      logger.atSevere().log("publishHost called for invalid host %s", hostName);
      return;
    }

    // Refresh the superordinate domain, since we shouldn't be publishing glue records if we are not
    // authoritative for the superordinate domain.
    publishDomain(getSecondLevelDomain(hostName, tld.get().toString()));
  }

  /**
   * Sync changes in a zone requested by publishDomain and publishHost to Cloud DNS.
   *
   * <p>The zone for the TLD must exist first in Cloud DNS and must be DNSSEC enabled.
   *
   * <p>The relevant resource records (including those of all subordinate hosts) will be retrieved
   * and the operation will be retried until the state of the retrieved zone data matches the
   * representation built via this writer.
   */
  @Override
  protected void commitUnchecked() {
    ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecordsCopy =
        ImmutableMap.copyOf(desiredRecords);
    retrier.callWithRetry(() -> mutateZone(desiredRecordsCopy), ZoneStateException.class);
    logger.atInfo().log("Wrote to Cloud DNS");
  }

  /** Returns the glue records for in-bailiwick nameservers for the given domain+records. */
  private Stream<String> filterGlueRecords(String domainName, Stream<ResourceRecordSet> records) {
    return records
        .filter(record -> record.getType().equals("NS"))
        .flatMap(record -> record.getRrdatas().stream())
        .filter(hostName -> hostName.endsWith("." + domainName) && !hostName.equals(domainName));
  }

  /** Mutate the zone with the provided map of hostnames to desired DNS records. */
  @VisibleForTesting
  void mutateZone(ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords) {
    logger.atInfo().log("Updating DNS records for hostname(s) %s.", desiredRecords.keySet());
    // Fetch all existing records for names that this writer is trying to modify
    ImmutableSet.Builder<ResourceRecordSet> flattenedExistingRecords = new ImmutableSet.Builder<>();

    // First, fetch the records for the given domains
    Map<String, List<ResourceRecordSet>> domainRecords =
        getResourceRecordsForDomains(desiredRecords.keySet());

    // add the records to the list of existing records
    domainRecords.values().forEach(flattenedExistingRecords::addAll);

    // Get the glue record host names from the given records
    ImmutableSet<String> hostsToRead =
        domainRecords
            .entrySet()
            .stream()
            .flatMap(entry -> filterGlueRecords(entry.getKey(), entry.getValue().stream()))
            .collect(toImmutableSet());

    // Then fetch and add the records for these hosts
    getResourceRecordsForDomains(hostsToRead).values().forEach(flattenedExistingRecords::addAll);

    // Flatten the desired records into one set.
    ImmutableSet.Builder<ResourceRecordSet> flattenedDesiredRecords = new ImmutableSet.Builder<>();
    desiredRecords.values().forEach(flattenedDesiredRecords::addAll);

    // Delete all existing records and add back the desired records
    try {
      updateResourceRecords(flattenedDesiredRecords.build(), flattenedExistingRecords.build());
    } catch (IOException e) {
      throw new RuntimeException(
          "Error updating DNS records for hostname(s) " + desiredRecords.keySet(), e);
    }
  }

  /**
   * Fetch the {@link ResourceRecordSet}s for the given domain names under this zone.
   *
   * <p>The provided domain should be in absolute form.
   */
  private Map<String, List<ResourceRecordSet>> getResourceRecordsForDomains(
      Set<String> domainNames) {
    logger.atFine().log("Fetching records for %s", domainNames);
    // As per Concurrent.transform() - if numThreads or domainNames.size() < 2, it will not use
    // threading.
    return ImmutableMap.copyOf(
        Concurrent.transform(
            domainNames,
            numThreads,
            domainName ->
                new SimpleImmutableEntry<>(domainName, getResourceRecordsForDomain(domainName))));
  }

  /**
   * Fetch the {@link ResourceRecordSet}s for the given domain name under this zone.
   *
   * <p>The provided domain should be in absolute form.
   */
  private List<ResourceRecordSet> getResourceRecordsForDomain(String domainName) {
    // TODO(b/70217860): do we want to use a retrier here?
    try {
      Dns.ResourceRecordSets.List listRecordsRequest =
          dnsConnection.resourceRecordSets().list(projectId, zoneName).setName(domainName);

      rateLimiter.acquire();
      return listRecordsRequest.execute().getRrsets();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Update {@link ResourceRecordSet}s under this zone.
   *
   * <p>This call should be used in conjunction with {@link #getResourceRecordsForDomains} in a
   * get-and-set retry loop.
   *
   * <p>See {@link "https://cloud.google.com/dns/troubleshooting"} for a list of errors produced by
   * the Google Cloud DNS API.
   *
   * @throws ZoneStateException if the operation could not be completely successfully because the
   *     records to delete do not exist, already exist or have been modified with different
   *     attributes since being queried. These errors will be retried.
   * @throws IOException on non-retryable API errors, e.g. invalid request.
   */
  private void updateResourceRecords(
      ImmutableSet<ResourceRecordSet> additions, ImmutableSet<ResourceRecordSet> deletions)
      throws IOException {
    // Find records that are both in additions and deletions, so we can remove them from both before
    // requesting the change. This is mostly for optimization reasons - not doing so doesn't affect
    // the result.
    ImmutableSet<ResourceRecordSet> intersection =
        Sets.intersection(additions, deletions).immutableCopy();
    logger.atInfo().log(
        "There are %d common items out of the %d items in 'additions' and %d items in 'deletions'",
        intersection.size(), additions.size(), deletions.size());
    // Exit early if we have nothing to update - dnsConnection doesn't work on empty changes
    if (additions.equals(deletions)) {
      logger.atInfo().log("Returning early because additions is the same as deletions");
      return;
    }
    Change change =
        new Change()
            .setAdditions(ImmutableList.copyOf(Sets.difference(additions, intersection)))
            .setDeletions(ImmutableList.copyOf(Sets.difference(deletions, intersection)));

    rateLimiter.acquire();
    try {
      dnsConnection.changes().create(projectId, zoneName, change).execute();
    } catch (GoogleJsonResponseException e) {
      GoogleJsonError err = e.getDetails();
      // We did something really wrong here, just give up and re-throw
      if (err == null || err.getErrors().size() > 1) {
        throw e;
      }
      String errorReason = err.getErrors().get(0).getReason();

      if (RETRYABLE_EXCEPTION_REASONS.contains(errorReason)) {
        throw new ZoneStateException(errorReason);
      } else {
        throw e;
      }
    }
  }

  /**
   * Returns the presentation format ending in a dot used for an absolute hostname.
   *
   * @param hostName the fully qualified hostname
   */
  private static String getAbsoluteHostName(String hostName) {
    return hostName.endsWith(".") ? hostName : hostName + ".";
  }

  /** Zone state on Cloud DNS does not match the expected state. */
  static class ZoneStateException extends RuntimeException {
    public ZoneStateException(String reason) {
      super("Zone state on Cloud DNS does not match the expected state: " + reason);
    }
  }
}
