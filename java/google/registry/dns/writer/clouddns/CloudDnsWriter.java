// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.model.EppResourceUtils.loadByUniqueId;

import com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.Change;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.config.ConfigModule.Config;
import google.registry.dns.writer.DnsWriter;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registries;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;
import google.registry.util.Retrier;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * {@link DnsWriter} implementation that talks to Google Cloud DNS.
 *
 * @see "https://cloud.google.com/dns/docs/"
 */
class CloudDnsWriter implements DnsWriter {

  /**
   * The name of the pricing engine, as used in {@code Registry.dnsWriter}. Remember to change
   * the value on affected Registry objects to prevent runtime failures.
   */
  public static final String NAME = "CloudDnsWriter";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  // This is the default max QPS for Cloud DNS. It can be increased by contacting the team
  // via the Quotas page on the Cloud Console.
  // TODO(shikhman): inject the RateLimiter
  private static final int CLOUD_DNS_MAX_QPS = 20;
  private static final RateLimiter rateLimiter = RateLimiter.create(CLOUD_DNS_MAX_QPS);
  private static final ImmutableSet<String> RETRYABLE_EXCEPTION_REASONS =
      ImmutableSet.of("preconditionFailed", "notFound", "alreadyExists");

  private final Clock clock;
  // TODO(shikhman): This uses @Config("transientFailureRetries") which may not be tuned for this
  // application.
  private final Retrier retrier;
  private final Duration defaultTtl;
  private final String projectId;
  private final String zoneName;
  private final Dns dnsConnection;
  private final ImmutableMap.Builder<String, ImmutableSet<ResourceRecordSet>>
      desiredRecordsBuilder = new ImmutableMap.Builder<>();

  @Inject
  CloudDnsWriter(
      Dns dnsConnection,
      @Config("projectId") String projectId,
      @DnsWriterZone String zoneName,
      @Config("dnsDefaultTtl") Duration defaultTtl,
      Clock clock,
      Retrier retrier) {
    this.dnsConnection = dnsConnection;
    this.projectId = projectId;
    this.zoneName = zoneName;
    this.defaultTtl = defaultTtl;
    this.clock = clock;
    this.retrier = retrier;
  }

  /** Publish the domain and all subordinate hosts. */
  @Override
  public void publishDomain(String domainName) {
    // Canonicalize name
    String absoluteDomainName = getAbsoluteHostName(domainName);

    // Load the target domain. Note that it can be null if this domain was just deleted.
    Optional<DomainResource> domainResource =
        Optional.fromNullable(loadByUniqueId(DomainResource.class, domainName, clock.nowUtc()));

    // Return early if no DNS records should be published.
    // desiredRecordsBuilder is populated with an empty set to indicate that all existing records
    // should be deleted.
    if (!domainResource.isPresent() || !domainResource.get().shouldPublishToDns()) {
      desiredRecordsBuilder.put(absoluteDomainName, ImmutableSet.<ResourceRecordSet>of());
      return;
    }

    ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

    // Construct DS records (if any).
    Set<DelegationSignerData> dsData = domainResource.get().getDsData();
    if (!dsData.isEmpty()) {
      HashSet<String> dsRrData = new HashSet<>();
      for (DelegationSignerData ds : dsData) {
        dsRrData.add(ds.toRrData());
      }

      if (!dsRrData.isEmpty()) {
        domainRecords.add(
            new ResourceRecordSet()
                .setName(absoluteDomainName)
                .setTtl((int) defaultTtl.getStandardSeconds())
                .setType("DS")
                .setKind("dns#resourceRecordSet")
                .setRrdatas(ImmutableList.copyOf(dsRrData)));
      }
    }


    // Construct NS records (if any).
    Set<String> nameserverData = domainResource.get().loadNameserverFullyQualifiedHostNames();
    if (!nameserverData.isEmpty()) {
      HashSet<String> nsRrData = new HashSet<>();
      for (String hostName : nameserverData) {
        nsRrData.add(getAbsoluteHostName(hostName));

        // Construct glue records for subordinate NS hostnames (if any)
        if (hostName.endsWith(domainName)) {
          publishSubordinateHost(hostName);
        }
      }

      if (!nsRrData.isEmpty()) {
        domainRecords.add(
            new ResourceRecordSet()
                .setName(absoluteDomainName)
                .setTtl((int) defaultTtl.getStandardSeconds())
                .setType("NS")
                .setKind("dns#resourceRecordSet")
                .setRrdatas(ImmutableList.copyOf(nsRrData)));
      }
    }

    desiredRecordsBuilder.put(absoluteDomainName, domainRecords.build());
    logger.finefmt(
        "Will write %s records for domain %s", domainRecords.build().size(), absoluteDomainName);
  }

  private void publishSubordinateHost(String hostName) {
    logger.infofmt("Publishing glue records for %s", hostName);
    // Canonicalize name
    String absoluteHostName = getAbsoluteHostName(hostName);

    // Load the target host. Note that it can be null if this host was just deleted.
    // desiredRecords is populated with an empty set to indicate that all existing records
    // should be deleted.
    Optional<HostResource> host =
        Optional.fromNullable(loadByUniqueId(HostResource.class, hostName, clock.nowUtc()));

    // Return early if the host is deleted.
    if (!host.isPresent()) {
      desiredRecordsBuilder.put(absoluteHostName, ImmutableSet.<ResourceRecordSet>of());
      return;
    }

    ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

    // Construct A and AAAA records (if any).
    HashSet<String> aRrData = new HashSet<>();
    HashSet<String> aaaaRrData = new HashSet<>();
    for (InetAddress ip : host.get().getInetAddresses()) {
      if (ip instanceof Inet4Address) {
        aRrData.add(ip.toString());
      } else {
        checkArgument(ip instanceof Inet6Address);
        aaaaRrData.add(ip.toString());
      }
    }

    if (!aRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultTtl.getStandardSeconds())
              .setType("A")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aRrData)));
    }

    if (!aaaaRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultTtl.getStandardSeconds())
              .setType("AAAA")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aaaaRrData)));
    }

    desiredRecordsBuilder.put(absoluteHostName, domainRecords.build());
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
      logger.severefmt("publishHost called for invalid host %s", hostName);
      return;
    }

    // Extract the superordinate domain name. The TLD and host may have several dots so this
    // must calculate a sublist.
    ImmutableList<String> hostParts = host.parts();
    ImmutableList<String> tldParts = tld.get().parts();
    ImmutableList<String> domainParts =
        hostParts.subList(hostParts.size() - tldParts.size() - 1, hostParts.size());
    String domain = Joiner.on(".").join(domainParts);

    // Refresh the superordinate domain, since we shouldn't be publishing glue records if we are not
    // authoritative for the superordinate domain.
    publishDomain(domain);
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
  public void close() {
    close(desiredRecordsBuilder.build());
  }

  @VisibleForTesting
  void close(ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords) {
    retrier.callWithRetry(getMutateZoneCallback(desiredRecords), ZoneStateException.class);
    logger.info("Wrote to Cloud DNS");
  }

  /**
   * Get a callback to mutate the zone with the provided {@code desiredRecords}.
   */
  @VisibleForTesting
  Callable<Void> getMutateZoneCallback(
      final ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords) {
    return new Callable<Void>() {
      @Override
      public Void call() throws IOException, ZoneStateException {
        // Fetch all existing records for names that this writer is trying to modify
        Builder<ResourceRecordSet> existingRecords = new Builder<>();
        for (String domainName : desiredRecords.keySet()) {
          List<ResourceRecordSet> existingRecordsForDomain =
              getResourceRecordsForDomain(domainName);
          existingRecords.addAll(existingRecordsForDomain);

          // Fetch glue records for in-bailiwick nameservers
          for (ResourceRecordSet record : existingRecordsForDomain) {
            if (!record.getType().equals("NS")) {
              continue;
            }
            for (String hostName : record.getRrdatas()) {
              if (hostName.endsWith(domainName) && !hostName.equals(domainName)) {
                existingRecords.addAll(getResourceRecordsForDomain(hostName));
              }
            }
          }
        }

        // Flatten the desired records into one set.
        Builder<ResourceRecordSet> flattenedDesiredRecords = new Builder<>();
        for (ImmutableSet<ResourceRecordSet> records : desiredRecords.values()) {
          flattenedDesiredRecords.addAll(records);
        }

        // Delete all existing records and add back the desired records
        updateResourceRecords(flattenedDesiredRecords.build(), existingRecords.build());
        return null;
      }
    };
  }

  /**
   * Fetch the {@link ResourceRecordSet}s for the given domain name under this zone.
   *
   * <p>The provided domain should be in absolute form.
   *
   * @throws IOException if the operation could not be completed successfully
   */
  private List<ResourceRecordSet> getResourceRecordsForDomain(String domainName)
      throws IOException {
    logger.finefmt("Fetching records for %s", domainName);
    Dns.ResourceRecordSets.List listRecordsRequest =
        dnsConnection.resourceRecordSets().list(projectId, zoneName).setName(domainName);

    rateLimiter.acquire();
    return listRecordsRequest.execute().getRrsets();
  }

  /**
   * Update {@link ResourceRecordSet}s under this zone.
   *
   * <p>This call should be used in conjunction with getResourceRecordsForDomain in a get-and-set
   * retry loop.
   *
   * <p>See {@link "https://cloud.google.com/dns/troubleshooting"} for a list of errors produced by
   * the Google Cloud DNS API.
   *
   * @throws IOException if the operation could not be completed successfully due to an
   *     uncorrectable error.
   * @throws ZoneStateException if the operation could not be completely successfully because the
   *     records to delete do not exist, already exist or have been modified with different
   *     attributes since being queried.
   */
  private void updateResourceRecords(
      ImmutableSet<ResourceRecordSet> additions, ImmutableSet<ResourceRecordSet> deletions)
      throws IOException, ZoneStateException {
    Change change = new Change().setAdditions(additions.asList()).setDeletions(deletions.asList());

    rateLimiter.acquire();
    try {
      dnsConnection.changes().create(projectId, zoneName, change).execute();
    } catch (GoogleJsonResponseException e) {
      List<ErrorInfo> errors = e.getDetails().getErrors();
      // We did something really wrong here, just give up and re-throw
      if (errors.size() > 1) {
        throw e;
      }
      String errorReason = errors.get(0).getReason();

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
