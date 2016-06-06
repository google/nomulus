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

package google.registry.dns.writer.dnsupdate;

import static com.google.common.base.Verify.verify;
import static google.registry.model.EppResourceUtils.loadByUniqueId;

import com.google.common.net.InternetDomainName;

import google.registry.config.ConfigModule.Config;
import google.registry.dns.writer.api.DnsWriter;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registries;
import google.registry.util.Clock;

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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import javax.inject.Inject;

/**
 * A DnsWriter that implements the DNS UPDATE protocol as specified in
 * <a href="https://tools.ietf.org/html/rfc2136">RFC 2136</a>. Publishes changes in the
 * domain-registry to a (capable) external DNS server, sometimes called a "hidden master". DNS
 * UPDATE messages are sent via a supplied "transport" class. For each publish call, a single
 * UPDATE message is created containing the records required to "synchronize" the DNS with the
 * current (at the time of processing) state of the registry, for the supplied domain/host.
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
 * <p>Each publish call is treated as an atomic update to the DNS. If an update fails an exception
 * is thrown, expecting the caller to retry the update later. The SOA record serial number is
 * implicitly incremented by the server on each UPDATE message, as required by RFC 2136. Care must
 * be taken to make sure the SOA serial number does not go backwards if the entire TLD (zone) is
 * "reset" to empty and republished.
 */
public class DnsUpdateWriter implements DnsWriter {

  private final Duration dnsTimeToLive;
  private final DnsMessageTransport transport;
  private final Clock clock;

  /**
   * Class constructor.
   *
   * @param dnsTimeToLive TTL used for any created resource records
   * @param transport the transport used to send/receive the UPDATE messages
   * @param clock a source of time
   */
  @Inject
  public DnsUpdateWriter(
      @Config("dnsUpdateTimeToLive") Duration dnsTimeToLive,
      DnsMessageTransport transport,
      Clock clock) {
    this.dnsTimeToLive = dnsTimeToLive;
    this.transport = transport;
    this.clock = clock;
  }

  @Override
  public void publishDomain(String domainName) {
    DomainResource domain = loadByUniqueId(DomainResource.class, domainName, clock.nowUtc());
    try {
      Update update = new Update(toAbsoluteName(findTldFromName(domainName)));
      update.delete(toAbsoluteName(domainName), Type.ANY);
      if (domain != null && domain.shouldPublishToDns()) {
        update.add(makeNameServerSet(domain));
        update.add(makeDelegationSignerSet(domain));
      }

      Message response = transport.send(update);
      verify(
          response.getRcode() == Rcode.NOERROR,
          "DNS server failed domain update for '%s' rcode: %s",
          domainName,
          Rcode.string(response.getRcode()));
    } catch (IOException e) {
      throw new RuntimeException("publishDomain failed: " + domainName, e);
    }
  }

  @Override
  public void publishHost(String hostName) {
    HostResource host = loadByUniqueId(HostResource.class, hostName, clock.nowUtc());
    try {
      Update update = new Update(toAbsoluteName(findTldFromName(hostName)));
      update.delete(toAbsoluteName(hostName), Type.ANY);
      if (host != null) {
        update.add(makeAddressSet(host));
        update.add(makeV6AddressSet(host));
      }

      Message response = transport.send(update);
      verify(
          response.getRcode() == Rcode.NOERROR,
          "DNS server failed host update for '%s' rcode: %s",
          hostName,
          Rcode.string(response.getRcode()));
    } catch (IOException e) {
      throw new RuntimeException("publishHost failed: " + hostName, e);
    }
  }

  /**
   * Does nothing. Publish calls are synchronous and atomic.
   */
  @Override
  public void close() {}

  private RRset makeDelegationSignerSet(DomainResource domain) throws TextParseException {
    RRset signerSet = new RRset();
    for (DelegationSignerData signerData : domain.getDsData()) {
      DSRecord dsRecord =
          new DSRecord(
              toAbsoluteName(domain.getFullyQualifiedDomainName()),
              DClass.IN,
              dnsTimeToLive.getStandardSeconds(),
              signerData.getKeyTag(),
              signerData.getAlgorithm(),
              signerData.getDigestType(),
              signerData.getDigest());
      signerSet.addRR(dsRecord);
    }
    return signerSet;
  }

  private RRset makeNameServerSet(DomainResource domain) throws TextParseException {
    RRset nameServerSet = new RRset();
    for (String hostName : domain.loadNameserverFullyQualifiedHostNames()) {
      NSRecord record =
          new NSRecord(
              toAbsoluteName(domain.getFullyQualifiedDomainName()),
              DClass.IN,
              dnsTimeToLive.getStandardSeconds(),
              toAbsoluteName(hostName));
      nameServerSet.addRR(record);
    }
    return nameServerSet;
  }

  private RRset makeAddressSet(HostResource host) throws TextParseException {
    RRset addressSet = new RRset();
    for (InetAddress address : host.getInetAddresses()) {
      if (address instanceof Inet4Address) {
        ARecord record =
            new ARecord(
                toAbsoluteName(host.getFullyQualifiedHostName()),
                DClass.IN,
                dnsTimeToLive.getStandardSeconds(),
                address);
        addressSet.addRR(record);
      }
    }
    return addressSet;
  }

  private RRset makeV6AddressSet(HostResource host) throws TextParseException {
    RRset addressSet = new RRset();
    for (InetAddress address : host.getInetAddresses()) {
      if (address instanceof Inet6Address) {
        AAAARecord record =
            new AAAARecord(
                toAbsoluteName(host.getFullyQualifiedHostName()),
                DClass.IN,
                dnsTimeToLive.getStandardSeconds(),
                address);
        addressSet.addRR(record);
      }
    }
    return addressSet;
  }

  private String findTldFromName(String name) {
    return Registries.findTldForNameOrThrow(InternetDomainName.from(name)).toString();
  }

  private Name toAbsoluteName(String name) throws TextParseException {
    return Name.fromString(name, Name.root);
  }
}
