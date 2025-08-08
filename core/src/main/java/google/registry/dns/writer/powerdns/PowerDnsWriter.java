// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.dns.writer.powerdns;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriter;
import google.registry.dns.writer.powerdns.client.PowerDNSClient;
import google.registry.dns.writer.powerdns.client.model.Cryptokey;
import google.registry.dns.writer.powerdns.client.model.Cryptokey.KeyType;
import google.registry.dns.writer.powerdns.client.model.Metadata;
import google.registry.dns.writer.powerdns.client.model.RRSet;
import google.registry.dns.writer.powerdns.client.model.RecordObject;
import google.registry.dns.writer.powerdns.client.model.TSIGKey;
import google.registry.dns.writer.powerdns.client.model.Zone;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.joda.time.Duration;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

/**
 * A DnsWriter that sends updates to a PowerDNS backend server. Extends the peer DnsUpdateWriter
 * class, which already handles the logic for aggregating DNS changes into a single update request.
 * This request is then converted into a PowerDNS Zone object and sent to the PowerDNS API.
 */
public class PowerDnsWriter extends DnsUpdateWriter {
  // Class configuration
  public static final String NAME = "PowerDnsWriter";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // PowerDNS configuration
  private final String tldZoneName;
  private final ImmutableList<String> rootNameServers;
  private final String soaName;
  private final Boolean dnssecEnabled;
  private final Boolean tsigEnabled;
  private final PowerDNSClient powerDnsClient;

  // Supported record types to synchronize with PowerDNS
  private static final ArrayList<String> supportedRecordTypes =
      new ArrayList<>(Arrays.asList("A", "AAAA", "DS", "NS"));

  // Zone ID cache configuration
  private static final ConcurrentHashMap<String, String> zoneIdCache = new ConcurrentHashMap<>();
  private static long zoneIdCacheExpiration = 0;
  private static int defaultZoneTtl = 3600; // 1 hour in seconds

  // DNSSEC configuration
  private static final String DNSSEC_ALGORITHM = "rsasha256";
  private static final String DNSSEC_SOA_EDIT = "INCREMENT-WEEKS";
  private static final int DNSSEC_KSK_BITS = 2048;
  private static final int DNSSEC_ZSK_BITS = 1024;
  private static final long DNSSEC_ZSK_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
  private static final long DNSSEC_ZSK_ACTIVATION_MS =
      1000L * 2 * defaultZoneTtl; // twice the default zone TTL in milliseconds
  private static final String DNSSEC_ZSK_EXPIRE_FLAG = "DNSSEC-ZSK-EXPIRE-DATE";
  private static final String DNSSEC_ZSK_ACTIVATION_FLAG = "DNSSEC-ZSK-ACTIVATION-DATE";

  // TSIG key configuration
  private static final String TSIG_KEY_NAME = "tsig";
  private static final String TSIG_KEY_ALGORITHM = "hmac-sha256";

  /**
   * Class constructor.
   *
   * @param tldZoneName the name of the TLD associated with the update
   * @param dnsDefaultATtl the default TTL for A records
   * @param dnsDefaultNsTtl the default TTL for NS records
   * @param dnsDefaultDsTtl the default TTL for DS records
   * @param powerDnsBaseUrl the base URL of the PowerDNS server
   * @param powerDnsApiKey the API key for the PowerDNS server
   * @param clock the clock to use for the PowerDNS writer
   */
  @Inject
  public PowerDnsWriter(
      @DnsWriterZone String tldZoneName,
      @Config("dnsDefaultATtl") Duration dnsDefaultATtl,
      @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl,
      @Config("powerDnsBaseUrl") String powerDnsBaseUrl,
      @Config("powerDnsApiKey") String powerDnsApiKey,
      @Config("powerDnsRootNameServers") ImmutableList<String> powerDnsRootNameServers,
      @Config("powerDnsSoaName") String powerDnsSoaName,
      @Config("powerDnsDnssecEnabled") Boolean powerDnsDnssecEnabled,
      @Config("powerDnsTsigEnabled") Boolean powerDnsTsigEnabled,
      Clock clock) {

    // call the DnsUpdateWriter constructor, omitting the transport parameter
    // since we don't need it for PowerDNS
    super(tldZoneName, dnsDefaultATtl, dnsDefaultNsTtl, dnsDefaultDsTtl, null, clock);

    // Initialize the PowerDNS client
    this.tldZoneName = getHostNameWithTrailingDot(tldZoneName);
    this.rootNameServers = powerDnsRootNameServers;
    this.soaName = powerDnsSoaName;
    this.dnssecEnabled = powerDnsDnssecEnabled;
    this.tsigEnabled = powerDnsTsigEnabled;
    this.powerDnsClient = new PowerDNSClient(powerDnsBaseUrl, powerDnsApiKey);
  }

  /**
   * Prepare a domain for staging in PowerDNS. Handles the logic to clean up orphaned glue records
   * and adds the domain records to the update.
   *
   * @param domainName the fully qualified domain name, with no trailing dot
   */
  @Override
  public void publishDomain(String domainName) {
    String normalizedDomainName = getHostNameWithoutTrailingDot(domainName);
    logger.atInfo().log("Staging domain %s for PowerDNS", normalizedDomainName);
    super.publishDomain(normalizedDomainName);
  }

  /**
   * Determine whether the host should be published as a glue record for this zone. If so, add the
   * host records to the update.
   *
   * @param hostName the fully qualified host name, with no trailing dot
   */
  @Override
  public void publishHost(String hostName) {
    String normalizedHostName = getHostNameWithoutTrailingDot(hostName);
    logger.atInfo().log("Staging host %s for PowerDNS", normalizedHostName);
    super.publishHost(normalizedHostName);
  }

  @Override
  protected void commitUnchecked() {
    try {
      // persist staged changes to PowerDNS
      logger.atInfo().log("Committing updates to PowerDNS for TLD %s", tldZoneName);

      // convert the update to a PowerDNS Zone object
      Zone zone = convertUpdateToZone(update);

      // call the PowerDNS API to commit the changes
      powerDnsClient.patchZone(zone);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Commit to PowerDNS failed for TLD: %s", tldZoneName);
      throw new RuntimeException("publishDomain failed for TLD: " + tldZoneName, e);
    }
  }

  /**
   * Convert the parent class's update object (org.xbill.DNS.Update) into a PowerDNS Zone object
   * (google.registry.dns.writer.powerdns.client.Zone)
   *
   * @param update the update object to convert
   * @return the PowerDNS Zone object
   * @throws IOException if the zone is not found
   */
  private Zone convertUpdateToZone(Update update) throws IOException {
    // Convert the Update object to a Zone object
    logger.atInfo().log("Converting PowerDNS TLD zone %s update: %s", tldZoneName, update);

    // generate a list of records to process
    List<Record> updateRecordsToProcess = new ArrayList<>();
    for (Record r : update.getSection(Section.UPDATE)) {
      // determine if any updates exist for this domain
      Boolean isAnyDomainUpdate =
          update.getSection(Section.UPDATE).stream()
              .anyMatch(record -> record.getName().equals(r.getName()) && !isDeleteRecord(record));

      // special processing for ANY record deletions
      if (isDeleteRecord(r) && Type.string(r.getType()).equals("ANY")) {
        // only add a deletion record if there are no other updates for this domain
        if (!isAnyDomainUpdate) {
          // add a delete record for each of the supported record types
          for (String recordType : supportedRecordTypes) {
            Record deleteRecord =
                Record.newRecord(r.getName(), Type.value(recordType), r.getDClass(), r.getTTL());
            updateRecordsToProcess.add(deleteRecord);
          }
        }
      } else {
        // add the record to the list of records to process
        updateRecordsToProcess.add(r);
      }
    }

    // Iterate the update records and prepare them as PowerDNS RRSet objects, referencing the
    // following source code to determine the usage of the org.xbill.DNS.Record object:
    //
    // https://www.javadoc.io/doc/dnsjava/dnsjava/3.2.1/org/xbill/DNS/Record.html
    // https://github.com/dnsjava/dnsjava/blob/master/src/main/java/org/xbill/DNS/Record.java#L324-L350
    ArrayList<RRSet> allRRSets = new ArrayList<RRSet>();
    for (Record r : updateRecordsToProcess) {
      // skip unsupported record types
      if (!supportedRecordTypes.contains(Type.string(r.getType()))) {
        logger.atInfo().log(
            "Skipping unsupported PowerDNS update record type: %s", Type.string(r.getType()));
        continue;
      }

      // determine if this is a record update or a record deletion
      Boolean isDelete = isDeleteRecord(r);

      // find an existing RRSET matching record name and type, or create a new one
      // if an existing RRSET is not found
      logger.atInfo().log("Processing PowerDNS TLD zone %s update record: %s", tldZoneName, r);
      RRSet rrSet =
          allRRSets.stream()
              .filter(
                  rrset ->
                      rrset.getName().equals(r.getName().toString())
                          && rrset.getType().equals(Type.string(r.getType()))
                          && ((isDelete && rrset.getChangeType() == RRSet.ChangeType.DELETE)
                              || (!isDelete && rrset.getChangeType() == RRSet.ChangeType.REPLACE)))
              .findFirst()
              .orElse(
                  appendRRSet(
                      allRRSets, r.getName().toString(), Type.string(r.getType()), r.getTTL()));

      // handle record updates and deletions
      if (isDelete) {
        // indicate that this is a record deletion
        rrSet.setChangeType(RRSet.ChangeType.DELETE);
      } else {
        // indicate that this is a record update
        rrSet.setChangeType(RRSet.ChangeType.REPLACE);

        // add the record content
        RecordObject recordObject = new RecordObject();
        recordObject.setContent(r.rdataToString());
        recordObject.setDisabled(false);

        // append the record to the RRSet
        rrSet.getRecords().add(recordObject);
      }
    }

    // prepare a PowerDNS zone object containing the TLD record updates using the RRSet objects
    // that have a valid change type
    Zone preparedTldZone =
        getTldZoneForUpdate(
            allRRSets.stream().filter(v -> v.getChangeType() != null).collect(Collectors.toList()));

    // return the prepared TLD zone
    logger.atInfo().log(
        "Successfully processed PowerDNS TLD zone %s update record: %s",
        tldZoneName, preparedTldZone);
    return preparedTldZone;
  }

  /**
   * Create a new RRSet object.
   *
   * @param rrsets the list of RRSets
   * @param name the name of the RRSet
   * @param type the type of the RRSet
   * @param ttl the TTL of the RRSet
   * @return the new RRSet object
   */
  private RRSet appendRRSet(List<RRSet> rrsets, String name, String type, long ttl) {
    // create the base PowerDNS RRSet object
    RRSet rrset = new RRSet();
    rrset.setName(getHostNameWithTrailingDot(name));
    rrset.setType(type);
    rrset.setTtl(ttl);
    rrset.setRecords(new ArrayList<RecordObject>());

    // add the RRSet to the list of RRSets
    rrsets.add(rrset);

    // return the new RRSet object
    return rrset;
  }

  /**
   * Create a new TLD zone for the TLD associated with the PowerDnsWriter. The zone will be created
   * with a basic SOA record and not yet configured with DNSSEC.
   *
   * @return the new TLD zone
   * @throws IOException if the TLD zone is not found
   */
  private Zone createZone() throws IOException {
    // base TLD zone object
    logger.atInfo().log("Creating new PowerDNS TLD zone %s", tldZoneName);
    Zone newTldZone = new Zone();
    newTldZone.setName(getHostNameWithTrailingDot(tldZoneName));
    newTldZone.setKind(Zone.ZoneKind.Master);

    // create an initial SOA record, which may be modified later by an administrator
    // or an automated onboarding process
    RRSet soaRecord = new RRSet();
    soaRecord.setChangeType(RRSet.ChangeType.REPLACE);
    soaRecord.setName(getHostNameWithTrailingDot(tldZoneName));
    soaRecord.setTtl(defaultZoneTtl);
    soaRecord.setType("SOA");

    // add content to the SOA record content from default configuration
    RecordObject soaRecordContent = new RecordObject();
    soaRecordContent.setContent(
        String.format(
            "%s %s 1 900 1800 6048000 %s",
            getHostNameWithTrailingDot(rootNameServers.get(0)),
            getHostNameWithTrailingDot(soaName),
            defaultZoneTtl));
    soaRecordContent.setDisabled(false);
    soaRecord.setRecords(new ArrayList<RecordObject>(Arrays.asList(soaRecordContent)));

    // create NS records, which may be modified later by an administrator
    RRSet nsRecord = new RRSet();
    nsRecord.setChangeType(RRSet.ChangeType.REPLACE);
    nsRecord.setName(getHostNameWithTrailingDot(tldZoneName));
    nsRecord.setTtl(defaultZoneTtl);
    nsRecord.setType("NS");

    // add content to the NS record content from default configuration
    nsRecord.setRecords(
        new ArrayList<RecordObject>(
            rootNameServers.stream()
                .map(
                    ns -> {
                      RecordObject nsRecordContent = new RecordObject();
                      nsRecordContent.setContent(getHostNameWithTrailingDot(ns));
                      nsRecordContent.setDisabled(false);
                      return nsRecordContent;
                    })
                .collect(Collectors.toList())));

    // add the SOA and NS record to the new TLD zone
    newTldZone.setRrsets(new ArrayList<RRSet>(Arrays.asList(soaRecord, nsRecord)));

    // create the TLD zone and log the result
    Zone createdTldZone = powerDnsClient.createZone(newTldZone);
    logger.atInfo().log("Successfully created PowerDNS TLD zone %s", tldZoneName);

    // return the created TLD zone
    return createdTldZone;
  }

  /**
   * Validate and synchronize zone configuration for the provided TLD zone. includes SOA, NS, TSIG,
   * and DNSSEC configuration.
   *
   * @param zone the TLD zone to validate
   */
  private void validateZoneConfig(Zone zone) throws IOException {
    // validate the SOA and root NS records
    validateSoaConfig(zone);

    // validate the NS records
    validateNsConfig(zone);

    // validate the TSIG key configuration
    validateTsigConfig(zone);

    // validate the DNSSEC configuration
    validateDnssecConfig(zone);
  }

  /**
   * Validate the SOA record for the TLD zone.
   *
   * @param zone the TLD zone to validate
   */
  private void validateSoaConfig(Zone zone) throws IOException {
    // retrieve the existing SOA record
    logger.atInfo().log("Validating SOA record for PowerDNS TLD zone %s", zone.getName());
    RRSet soaRecord =
        zone.getRrsets().stream()
            .filter(rrset -> rrset.getType().equals("SOA"))
            .findFirst()
            .orElse(null);
    if (soaRecord == null || soaRecord.getRecords() == null) {
      throw new IOException("Invalid SOA record state for PowerDNS TLD zone " + zone.getName());
    }

    // retrieve the SOA record RRSet content exists
    RecordObject soaRecordContent = soaRecord.getRecords().get(0);
    if (soaRecordContent == null || soaRecordContent.getContent() == null) {
      throw new IOException(
          "Invalid SOA record content state for PowerDNS TLD zone " + zone.getName());
    }

    // validate the SOA record content exists
    String soaRecordContentString = soaRecordContent.getContent();
    if (soaRecordContentString == null) {
      throw new IOException(
          "Invalid SOA record content data for PowerDNS TLD zone " + zone.getName());
    }

    // validate the SOA string starts with the first root name server and the SOA contact
    // name found in the registry configuration
    if (soaRecordContentString.startsWith(
        String.format(
            "%s %s ",
            getHostNameWithTrailingDot(rootNameServers.get(0)),
            getHostNameWithTrailingDot(soaName)))) {
      logger.atInfo().log(
          "Successfully validated SOA record for PowerDNS TLD zone %s", zone.getName());
      return;
    }

    // update the SOA record to the expected value
    logger.atWarning().log("Updating SOA record for PowerDNS TLD zone %s", zone.getName());
    RRSet newSoaRecord = new RRSet();
    newSoaRecord.setChangeType(RRSet.ChangeType.REPLACE);
    newSoaRecord.setName(getHostNameWithTrailingDot(zone.getName()));
    newSoaRecord.setTtl(defaultZoneTtl);
    newSoaRecord.setType("SOA");

    // add content to the SOA record content from default configuration
    RecordObject newSoaRecordContent = new RecordObject();
    newSoaRecordContent.setContent(
        String.format(
            "%s %s %s 900 1800 6048000 %s",
            getHostNameWithTrailingDot(rootNameServers.get(0)),
            getHostNameWithTrailingDot(soaName),
            zone.getSerial(),
            defaultZoneTtl));
    newSoaRecordContent.setDisabled(false);
    newSoaRecord.setRecords(new ArrayList<RecordObject>(Arrays.asList(newSoaRecordContent)));

    // add the SOA to the TLD zone
    zone.setRrsets(new ArrayList<RRSet>(Arrays.asList(newSoaRecord)));

    // call the PowerDNS API to commit the changes
    powerDnsClient.patchZone(zone);
    logger.atInfo().log("Successfully updated SOA record for PowerDNS TLD zone %s", zone.getName());
  }

  /**
   * Validate the NS records for the TLD zone.
   *
   * @param zone the TLD zone to validate
   */
  private void validateNsConfig(Zone zone) throws IOException {
    // retrieve the existing NS records
    logger.atInfo().log("Validating NS records for PowerDNS TLD zone %s", zone.getName());
    RRSet nsRecord =
        zone.getRrsets().stream()
            .filter(
                rrset ->
                    rrset.getType().equals("NS")
                        && getHostNameWithoutTrailingDot(rrset.getName())
                            .equals(getHostNameWithoutTrailingDot(zone.getName())))
            .findFirst()
            .orElse(null);
    if (nsRecord == null || nsRecord.getRecords() == null) {
      throw new IOException("Invalid NS record state for PowerDNS TLD zone " + zone.getName());
    }

    // retrieve normalized list of existing NS record content
    List<String> existingNsRecords =
        nsRecord.getRecords().stream()
            .map(r -> getHostNameWithoutTrailingDot(r.getContent()))
            .collect(Collectors.toList());

    // make normalized list of expected NS record content
    List<String> expectedNsRecords =
        rootNameServers.stream()
            .map(r -> getHostNameWithoutTrailingDot(r))
            .collect(Collectors.toList());

    // validate the existing NS record array elements match the expected elements
    // found in the root name servers list
    if (existingNsRecords.equals(expectedNsRecords)) {
      logger.atInfo().log(
          "Successfully validated NS records for PowerDNS TLD zone %s", zone.getName());
      return;
    }

    // update the NS records to the expected value
    logger.atWarning().log(
        "Updating NS records for PowerDNS TLD zone %s. Existing=%s, New=%s",
        zone.getName(), existingNsRecords, expectedNsRecords);
    RRSet newNsRecord = new RRSet();
    newNsRecord.setChangeType(RRSet.ChangeType.REPLACE);
    newNsRecord.setName(getHostNameWithTrailingDot(zone.getName()));
    newNsRecord.setTtl(defaultZoneTtl);
    newNsRecord.setType("NS");

    // add content to the NS record content from default configuration
    newNsRecord.setRecords(
        new ArrayList<RecordObject>(
            rootNameServers.stream()
                .map(
                    ns -> {
                      RecordObject nsRecordContent = new RecordObject();
                      nsRecordContent.setContent(getHostNameWithTrailingDot(ns));
                      nsRecordContent.setDisabled(false);
                      return nsRecordContent;
                    })
                .collect(Collectors.toList())));

    // add the NS record to the TLD zone
    zone.setRrsets(new ArrayList<RRSet>(Arrays.asList(newNsRecord)));

    // call the PowerDNS API to commit the changes
    powerDnsClient.patchZone(zone);
    logger.atInfo().log("Successfully updated NS records for PowerDNS TLD zone %s", zone.getName());
  }

  /**
   * Validate the TSIG key configuration for the PowerDNS server. Ensures a TSIG key associated with
   * the TLD zone is available for use, and detects whether the TLD zone has been configured to use
   * the TSIG key during AXFR replication. Instructions are provided in the logs on how to configure
   * both the primary and secondary DNS servers with the expected TSIG key.
   *
   * @param zone the TLD zone to validate
   */
  private void validateTsigConfig(Zone zone) throws IOException {
    // check if TSIG configuration is required
    if (!tsigEnabled) {
      logger.atInfo().log(
          "TSIG validation is not required for PowerDNS TLD zone %s", zone.getName());
      return;
    }

    // calculate the zone TSIG key name
    logger.atInfo().log("Validating TSIG configuration for PowerDNS TLD zone %s", zone.getName());
    String zoneTsigKeyName =
        String.format("%s-%s", getHostNameWithoutTrailingDot(zone.getName()), TSIG_KEY_NAME);

    // validate the named TSIG key is present in the PowerDNS server
    try {
      // check for existing TSIG key, which throws an exception if it is not found
      powerDnsClient.getTSIGKey(zoneTsigKeyName);
    } catch (Exception e) {
      // create the TSIG key
      logger.atInfo().log(
          "Creating TSIG key '%s' for PowerDNS TLD zone %s", zoneTsigKeyName, zone.getName());
      powerDnsClient.createTSIGKey(TSIGKey.createTSIGKey(zoneTsigKeyName, TSIG_KEY_ALGORITHM));
    }
    logger.atInfo().log(
        "Validated TSIG key '%s' (%s) is available for AXFR replication to secondary servers for"
            + " TLD zone %s. Retrieve the key using 'pdnsutil list-tsig-keys' in a secure"
            + " environment and apply the key to the secondary server configuration.",
        zoneTsigKeyName, TSIG_KEY_ALGORITHM, zone.getName());

    // ensure the TSIG-ALLOW-AXFR metadata is set to the current TSIG key name
    try {
      Metadata metadata = powerDnsClient.getMetadata(zone.getId(), "TSIG-ALLOW-AXFR");
      // validate the metadata contains the expected TSIG key name
      if (!metadata.getMetadata().contains(zoneTsigKeyName)) {
        throw new IOException("missing expected TSIG-ALLOW-AXFR value");
      }
      logger.atInfo().log(
          "Validated PowerDNS TLD zone %s is ready for AXFR replication using TSIG key '%s'",
          zone.getName(), zoneTsigKeyName);
    } catch (IOException e) {
      // log the missing metadata with instructions on how to configure it
      logger.atSevere().log(
          "PowerDNS TLD zone %s is not configured for AXFR replication using TSIG key '%s'."
              + " Configure the replication using 'pdnsutil activate-tsig-key %s %s primary' in a"
              + " secure environment.",
          zoneTsigKeyName, zone.getName(), zone.getName(), zoneTsigKeyName);
    }
  }

  /**
   * Validate the DNSSEC configuration for the TLD zone. If DNSSEC is not enabled, it will be
   * enabled and the KSK and ZSK entries will be created. If DNSSEC is enabled, the ZSK expiration
   * date will be checked and the ZSK will be rolled over if it has expired.
   *
   * @param zone the TLD zone to validate
   */
  private void validateDnssecConfig(Zone zone) {
    // check if DNSSEC configuration is required
    if (!dnssecEnabled) {
      logger.atInfo().log(
          "DNSSEC validation is not required for PowerDNS TLD zone %s", zone.getName());
      return;
    }

    try {
      // check if DNSSEC is already enabled for the TLD zone
      if (!zone.getDnssec()) {
        // DNSSEC is not enabled, so we need to enable it
        logger.atInfo().log("Enabling DNSSEC for PowerDNS TLD zone %s", zone.getName());

        // create the KSK and ZSK entries for the TLD zone
        Cryptokey newKsk =
            powerDnsClient.createCryptokey(
                zone.getId(),
                Cryptokey.createCryptokey(
                    KeyType.ksk, DNSSEC_KSK_BITS, true, true, DNSSEC_ALGORITHM));
        powerDnsClient.createCryptokey(
            zone.getId(),
            Cryptokey.createCryptokey(KeyType.zsk, DNSSEC_ZSK_BITS, true, true, DNSSEC_ALGORITHM));

        // create the SOA-EDIT metadata entry for the TLD zone
        powerDnsClient.createMetadata(
            zone.getId(), Metadata.createMetadata("SOA-EDIT", Arrays.asList(DNSSEC_SOA_EDIT)));

        // update the zone account field with the expiration timestamp
        Zone updatedZone = new Zone();
        updatedZone.setId(zone.getId());
        updatedZone.setApiRectify(true);
        updatedZone.setAccount(
            String.format(
                "%s:%s",
                DNSSEC_ZSK_EXPIRE_FLAG, System.currentTimeMillis() + DNSSEC_ZSK_EXPIRY_MS));
        powerDnsClient.putZone(updatedZone);

        // attempt to manually rectify the TLD zone
        try {
          logger.atInfo().log("Rectifying PowerDNS TLD zone %s", zone.getName());
          powerDnsClient.rectifyZone(zone.getId());
        } catch (Exception rectifyException) {
          logger.atWarning().withCause(rectifyException).log(
              "Failed to complete rectification of PowerDNS TLD zone %s", zone.getName());
        }

        // retrieve the zone and print the new DS values
        logger.atInfo().log(
            "Successfully enabled DNSSEC for PowerDNS TLD zone %s, expected root DS=%s",
            zone.getName(),
            newKsk.getDs().stream()
                .map(ds -> String.format("IN DS %s", ds))
                .collect(Collectors.toList()));
      } else {
        // DNSSEC is enabled, so we need to validate the configuration
        logger.atInfo().log(
            "Validating existing DNSSEC configuration for PowerDNS TLD zone %s", zone.getName());

        // list all crypto keys for the TLD zone
        List<Cryptokey> cryptokeys = powerDnsClient.listCryptokeys(zone.getId());

        // identify the KSK and ZSK records
        Cryptokey activeZsk =
            cryptokeys.stream()
                .filter(c -> c.getActive() && c.getKeytype() == KeyType.zsk)
                .findFirst()
                .orElse(null);
        Cryptokey activeKsk =
            cryptokeys.stream()
                .filter(c -> c.getActive() && c.getKeytype() == KeyType.ksk)
                .findFirst()
                .orElse(null);

        // validate the KSK and ZSK records are present
        if (activeKsk == null || activeZsk == null) {
          // log the error and continue
          logger.atWarning().log(
              "Unable to validate DNSSEC configuration with active KSK and ZSK records for PowerDNS"
                  + " TLD zone %s",
              zone.getName());
          return;
        }

        // log the DS records associated with the KSK record
        logger.atInfo().log(
            "Validated KSK and ZSK records for PowerDNS TLD zone %s, expected root DS=%s",
            zone.getName(),
            activeKsk.getDs().stream()
                .map(ds -> String.format("IN DS %s", ds))
                .collect(Collectors.toList()));

        // check for a ZSK expiration flag
        if (zone.getAccount().contains(DNSSEC_ZSK_EXPIRE_FLAG)) {
          // check for an expired ZSK expiration date
          String dnssecZskExpireDate = Iterables.get(Splitter.on(':').split(zone.getAccount()), 1);
          if (System.currentTimeMillis() > Long.parseLong(dnssecZskExpireDate)) {
            // start a ZSK rollover
            logger.atInfo().log(
                "ZSK has expired, starting rollover for PowerDNS TLD zone %s", zone.getName());

            // create a new inactive ZSK
            powerDnsClient.createCryptokey(
                zone.getId(),
                Cryptokey.createCryptokey(
                    KeyType.zsk, DNSSEC_ZSK_BITS, false, true, DNSSEC_ALGORITHM));

            // update the zone account field with the activation timestamp
            Zone updatedZone = new Zone();
            updatedZone.setId(zone.getId());
            updatedZone.setAccount(
                String.format(
                    "%s:%s",
                    DNSSEC_ZSK_ACTIVATION_FLAG,
                    System.currentTimeMillis() + DNSSEC_ZSK_ACTIVATION_MS));
            powerDnsClient.putZone(updatedZone);

            // log the rollover event
            logger.atInfo().log(
                "Successfully started ZSK rollover for PowerDNS TLD zone %s", zone.getName());
          } else {
            // ZSK is not expired, so we need to log the current ZSK activation date
            logger.atInfo().log(
                "DNSSEC configuration for PowerDNS TLD zone %s is valid for another %s seconds",
                zone.getName(),
                (Long.parseLong(dnssecZskExpireDate) - System.currentTimeMillis()) / 1000);
          }
        }

        // check for a ZSK rollover key activation flag
        else if (zone.getAccount().contains(DNSSEC_ZSK_ACTIVATION_FLAG)) {
          // check for a ZSK activation date
          String dnssecZskActivationDate =
              Iterables.get(Splitter.on(':').split(zone.getAccount()), 1);
          if (System.currentTimeMillis() > Long.parseLong(dnssecZskActivationDate)) {
            // ZSK activation window has elapsed, so we need to activate the ZSK
            logger.atInfo().log(
                "ZSK activation window has elapsed, activating ZSK for PowerDNS TLD zone %s",
                zone.getName());

            // identify the inactive ZSK
            Cryptokey inactiveZsk =
                cryptokeys.stream()
                    .filter(c -> !c.getActive() && c.getKeytype() == KeyType.zsk)
                    .findFirst()
                    .orElse(null);

            // if both keys are found, complete the ZSK rollover
            if (activeZsk != null && inactiveZsk != null) {
              // activate the inactive ZSK
              inactiveZsk.setActive(true);
              powerDnsClient.modifyCryptokey(zone.getId(), inactiveZsk);

              // delete the active ZSK
              powerDnsClient.deleteCryptokey(zone.getId(), activeZsk.getId());

              // update the zone account field with the expiration timestamp
              Zone updatedZone = new Zone();
              updatedZone.setId(zone.getId());
              updatedZone.setAccount(
                  String.format(
                      "%s:%s",
                      DNSSEC_ZSK_EXPIRE_FLAG, System.currentTimeMillis() + DNSSEC_ZSK_EXPIRY_MS));
              powerDnsClient.putZone(updatedZone);

              // log the ZSK rollover event
              logger.atInfo().log(
                  "Successfully completed ZSK rollover for PowerDNS TLD zone %s", zone.getName());
            } else {
              // unable to complete the ZSK rollover
              logger.atSevere().log(
                  "Unable to locate active and inactive ZSKs for PowerDNS TLD zone %s. Manual"
                      + " intervention required to complete the ZSK rollover.",
                  zone.getName());
              return;
            }
          } else {
            // ZSK activation date has not yet elapsed, so we need to log the current ZSK activation
            // date
            logger.atInfo().log(
                "ZSK rollover for PowerDNS TLD zone %s is in progress for another %s seconds",
                zone.getName(),
                (Long.parseLong(dnssecZskActivationDate) - System.currentTimeMillis()) / 1000);
          }
        }
      }
    } catch (Exception e) {
      // log the error gracefully and allow processing to continue
      logger.atSevere().withCause(e).log(
          "Failed to validate DNSSEC configuration for PowerDNS TLD zone %s", zone.getName());
    }
  }

  /**
   * Returns the host name with a trailing dot.
   *
   * @param hostName the fully qualified hostname
   * @return the host name with a trailing dot
   */
  private String getHostNameWithTrailingDot(String hostName) {
    String normalizedHostName = hostName.toLowerCase(Locale.US).trim();
    return normalizedHostName.endsWith(".") ? normalizedHostName : normalizedHostName + '.';
  }

  /**
   * Returns the host name without the trailing dot.
   *
   * @param hostName the fully qualified hostname
   * @return the sanitized host name
   */
  private String getHostNameWithoutTrailingDot(String hostName) {
    // return the host name without the trailing dot
    String normalizedHostName = hostName.toLowerCase(Locale.US).trim();
    return normalizedHostName.endsWith(".")
        ? normalizedHostName.substring(0, normalizedHostName.length() - 1)
        : normalizedHostName;
  }

  /**
   * Prepare the TLD zone for updates by clearing the RRSets and incrementing the serial number.
   *
   * @param records the set of RRSet records that will be sent to the PowerDNS API
   * @return the prepared TLD zone
   */
  private Zone getTldZoneForUpdate(List<RRSet> records) throws IOException {
    Zone tldZone = new Zone();
    tldZone.setId(getTldZoneId());
    tldZone.setName(getHostNameWithoutTrailingDot(tldZoneName));
    tldZone.setRrsets(records);
    return tldZone;
  }

  /**
   * Get the TLD zone by name and validate the zone's configuration before returning.
   *
   * @return the TLD zone
   * @throws IOException if the TLD zone is not found
   */
  private Zone getAndValidateTldZoneByName() throws IOException {
    // retrieve an existing TLD zone by name
    for (Zone zone : powerDnsClient.listZones()) {
      if (getHostNameWithoutTrailingDot(zone.getName())
          .equals(getHostNameWithoutTrailingDot(tldZoneName))) {
        // retrieve full zone details
        Zone fullZone = powerDnsClient.getZone(zone.getId());

        // validate the zone's configuration
        validateZoneConfig(fullZone);
        return fullZone;
      }
    }

    // attempt to create a new TLD zone if it does not exist
    try {
      // create a new TLD zone
      Zone zone = createZone();

      // validate the zone's configuration
      validateZoneConfig(zone);
      return zone;
    } catch (Exception e) {
      // log the error and continue
      logger.atWarning().log("Failed to create PowerDNS TLD zone %s: %s", tldZoneName, e);
    }

    // otherwise, throw an exception
    throw new IOException("TLD zone not found: " + tldZoneName);
  }

  /**
   * Get the TLD zone ID for the given TLD zone name from the cache, or compute it if it is not
   * present in the cache. This method is synchronized since it may result in a new TLD zone being
   * created and DNSSEC being configured, and this should only happen once.
   *
   * @return the ID of the TLD zone
   */
  private synchronized String getTldZoneId() throws IOException {
    // clear the cache if it has expired
    if (zoneIdCacheExpiration < System.currentTimeMillis()) {
      logger.atInfo().log("Clearing PowerDNS TLD zone ID cache");
      zoneIdCache.clear();
      zoneIdCacheExpiration = System.currentTimeMillis() + 1000 * 60 * 60; // 1 hour
    }

    // retrieve the TLD zone ID from the cache or retrieve it from the PowerDNS API
    // if not available in the cache
    String zoneId =
        zoneIdCache.computeIfAbsent(
            tldZoneName,
            key -> {
              try {
                // retrieve the TLD zone by name, which may result from an existing zone or
                // be dynamically created if the zone does not exist
                Zone tldZone = getAndValidateTldZoneByName();

                // return the TLD zone ID, which will be cached for the next hour
                return tldZone.getId();
              } catch (IOException e) {
                // log the error and return a null value to indicate failure
                logger.atWarning().log(
                    "Failed to get PowerDNS TLD zone ID for %s: %s", tldZoneName, e);
                return null;
              }
            });

    // if the TLD zone ID is not found, throw an exception
    if (zoneId == null) {
      throw new IOException("TLD zone not found: " + tldZoneName);
    }

    // return the TLD zone ID
    return zoneId;
  }

  /**
   * Determine if a record is a delete record.
   *
   * @param r the record to check
   * @return true if the record is a delete record, false otherwise
   */
  private Boolean isDeleteRecord(Record r) {
    return r.getTTL() == 0 && r.rdataToString().equals("");
  }
}
