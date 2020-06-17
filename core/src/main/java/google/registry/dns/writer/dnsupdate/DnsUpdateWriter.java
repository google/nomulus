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

package google.registry.dns.writer.dnsupdate;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.collect.Sets.union;
import static google.registry.model.EppResourceUtils.loadByForeignKey;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.BaseDnsWriter;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registries;
import google.registry.util.Clock;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Duration;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

/**
 * A DnsWriter that implements the DNS UPDATE protocol as specified in
 * <a href="https://tools.ietf.org/html/rfc2136">RFC 2136</a>. Publishes changes in the
 * domain-registry to a (capable) external DNS server, sometimes called a "hidden master". DNS
 * UPDATE messages are sent via a supplied "transport" class.
 *
 * On call to {@link #commit()}, a single UPDATE message is created containing the records required
 * to "synchronize" the DNS with the current (at the time of processing) state of the registry, for
 * the supplied domain/host.
 *
 * <p>The general strategy of the publish methods is to delete <em>all</em> resource records of any
 * <em>type</em> that match the exact domain/host name supplied. And then for create/update cases,
 * add any required records. Deleting all records of any type assumes that the registry is
 * authoritative for all records for names in the zone. This seems appropriate for a TLD DNS server,
 * which should only contain records required for proper DNS delegation.
 *
 * <p>Only NS, DS, A, and AAAA records are published, and in particular no DNSSEC signing is done
 * assuming that this will be done by a third party DNS provider.
 *
 * <p>Each commit call is treated as an atomic update to the DNS. If a commit fails an exception
 * is thrown. The SOA record serial number is implicitly incremented by the server on each UPDATE
 * message, as required by RFC 2136. Care must be taken to make sure the SOA serial number does not
 * go backwards if the entire TLD (zone) is "reset" to empty and republished.
 */
public class DnsUpdateWriter extends BaseDnsWriter {

  /**
   * The name of the pricing engine, as used in {@code Registry.dnsWriter}. Remember to change
   * the value on affected Registry objects to prevent runtime failures.
   */
  public static final String NAME = "DnsUpdateWriter";

  private final Duration dnsDefaultATtl;
  private final Duration dnsDefaultNsTtl;
  private final Duration dnsDefaultDsTtl;
  private final DnsMessageTransport transport;
  private final Clock clock;
  private final Update update;
  private final String zoneName;

  /**
   * Class constructor.
   *
   * @param dnsDefaultATtl TTL used for any created resource records
   * @param dnsDefaultNsTtl TTL used for any created nameserver records
   * @param dnsDefaultDsTtl TTL used for any created DS records
   * @param transport the transport used to send/receive the UPDATE messages
   * @param clock a source of time
   */
  @Inject
  public DnsUpdateWriter(
      @DnsWriterZone String zoneName,
      @Config("dnsDefaultATtl") Duration dnsDefaultATtl,
      @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl,
      DnsMessageTransport transport,
      Clock clock) {
    this.zoneName = zoneName;
    this.update = new Update(toAbsoluteName(zoneName));
    this.dnsDefaultATtl = dnsDefaultATtl;
    this.dnsDefaultNsTtl = dnsDefaultNsTtl;
    this.dnsDefaultDsTtl = dnsDefaultDsTtl;
    this.transport = transport;
    this.clock = clock;
  }

  /**
   * Publish the domain, while keeping tracking of which host refresh quest triggered this domain
   * refresh. Delete the requesting host in addition to all subordinate hosts.
   *
   * @param domainName the fully qualified domain name, with no trailing dot
   * @param requestingHostName the fully qualified host name, with no trailing dot, that triggers
   *     this domain refresh request
   */
  private void publishDomain(String domainName, String requestingHostName) {
    Optional<DomainBase> domainOptional =
        loadByForeignKey(DomainBase.class, domainName, clock.nowUtc());
    update.delete(toAbsoluteName(domainName), Type.ANY);
    // If the domain is now deleted, then don't update DNS for it.
    if (domainOptional.isPresent()) {
      DomainBase domain = domainOptional.get();
      // As long as the domain exists, orphan glues should be cleaned.
      deleteSubordinateHostAddressSet(domain, requestingHostName, update);
      if (domain.shouldPublishToDns()) {
        addInBailiwickNameServerSet(domain, update);
        update.add(makeNameServerSet(domain));
        update.add(makeDelegationSignerSet(domain));
      }
    }
  }

  @Override
  public void publishDomain(String domainName) {
    publishDomain(domainName, null);
  }

  @Override
  public void publishHost(String hostName) {
    // Get the superordinate domain name of the host.
    InternetDomainName host = InternetDomainName.from(hostName);
    ImmutableList<String> hostParts = host.parts();
    Optional<InternetDomainName> tld = Registries.findTldForName(host);

    // host not managed by our registry, no need to update DNS.
    if (!tld.isPresent()) {
      return;
    }

    ImmutableList<String> tldParts = tld.get().parts();
    ImmutableList<String> domainParts =
        hostParts.subList(hostParts.size() - tldParts.size() - 1, hostParts.size());
    String domain = Joiner.on(".").join(domainParts);

    // Refresh the superordinate domain, always delete the host first to ensure idempotency,
    // and only publish the host if it is a glue record.
    publishDomain(domain, hostName);
  }

  @Override
  protected void commitUnchecked() {
    try {
      Message response = transport.send(update);
      verify(
          response.getRcode() == Rcode.NOERROR,
          "DNS server failed domain update for '%s' rcode: %s",
          zoneName,
          Rcode.string(response.getRcode()));
    } catch (IOException e) {
      throw new RuntimeException("publishDomain failed for zone: " + zoneName, e);
    }
  }

  private RRset makeDelegationSignerSet(DomainBase domain) {
    RRset signerSet = new RRset();
    for (DelegationSignerData signerData : domain.getDsData()) {
      DSRecord dsRecord =
          new DSRecord(
              toAbsoluteName(domain.getDomainName()),
              DClass.IN,
              dnsDefaultDsTtl.getStandardSeconds(),
              signerData.getKeyTag(),
              signerData.getAlgorithm(),
              signerData.getDigestType(),
              signerData.getDigest());
      signerSet.addRR(dsRecord);
    }
    return signerSet;
  }

  private void deleteSubordinateHostAddressSet(
      DomainBase domain, String additionalHost, Update update) {
    for (String hostName :
        union(
            domain.getSubordinateHosts(),
            (additionalHost == null
                ? ImmutableSet.of()
                : ImmutableSet.of(additionalHost)))) {
      update.delete(toAbsoluteName(hostName), Type.ANY);
    }
  }

  private void addInBailiwickNameServerSet(DomainBase domain, Update update) {
    for (String hostName :
        intersection(domain.loadNameserverHostNames(), domain.getSubordinateHosts())) {
      Optional<HostResource> host = loadByForeignKey(HostResource.class, hostName, clock.nowUtc());
      checkState(host.isPresent(), "Host %s cannot be loaded", hostName);
      update.add(makeAddressSet(host.get()));
      update.add(makeV6AddressSet(host.get()));
    }
  }

  private RRset makeNameServerSet(DomainBase domain) {
    RRset nameServerSet = new RRset();
    for (String hostName : domain.loadNameserverHostNames()) {
      NSRecord record =
          new NSRecord(
              toAbsoluteName(domain.getDomainName()),
              DClass.IN,
              dnsDefaultNsTtl.getStandardSeconds(),
              toAbsoluteName(hostName));
      nameServerSet.addRR(record);
    }
    return nameServerSet;
  }

  private RRset makeAddressSet(HostResource host) {
    RRset addressSet = new RRset();
    for (InetAddress address : host.getInetAddresses()) {
      if (address instanceof Inet4Address) {
        ARecord record =
            new ARecord(
                toAbsoluteName(host.getHostName()),
                DClass.IN,
                dnsDefaultATtl.getStandardSeconds(),
                address);
        addressSet.addRR(record);
      }
    }
    return addressSet;
  }

  private RRset makeV6AddressSet(HostResource host) {
    RRset addressSet = new RRset();
    for (InetAddress address : host.getInetAddresses()) {
      if (address instanceof Inet6Address) {
        AAAARecord record =
            new AAAARecord(
                toAbsoluteName(host.getHostName()),
                DClass.IN,
                dnsDefaultATtl.getStandardSeconds(),
                address);
        addressSet.addRR(record);
      }
    }
    return addressSet;
  }

  private Name toAbsoluteName(String name) {
    try {
      return Name.fromString(name, Name.root);
    } catch (TextParseException e) {
      throw new RuntimeException(
          String.format("toAbsoluteName failed for name: %s in zone: %s", name, zoneName), e);
    }
  }
}
