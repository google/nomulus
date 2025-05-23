# Copyright 2025 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

## Create a new zone
#
#  The following commands are used to create a new zone in PowerDNS.

# Specify a TLD name on which to operate. In this example, the TLD is "tldtest1", 
# which specifies a zone for SLDs like "mydomain.tldtest1".
ZONE=tldtest1

./pdnsutil create-zone $ZONE
./pdnsutil set-kind $ZONE primary
./pdnsutil add-record $ZONE SOA "a.gtld-servers.net. nstld.verisign-grs.com. 1 900 1800 6048000 3600"

## DNSSEC Configuration
#
#  The following commands are used to setup DNSSEC for a given zone. Assumes the
#  user is running commands directly on the PowerDNS server.
#

./pdnsutil add-zone-key $ZONE ksk 2048 active published rsasha256
./pdnsutil add-zone-key $ZONE zsk 1024 active published rsasha256
./pdnsutil set-meta $ZONE SOA-EDIT INCREMENT-WEEKS
./pdnsutil set-publish-cdnskey $ZONE
./pdnsutil rectify-zone $ZONE
./pdnsutil show-zone $ZONE

## Example dig output after DNSSEC enabled
#
# ➜ dig +dnssec tldtest1 @127.0.0.1
# 
# ; <<>> DiG 9.10.6 <<>> +dnssec tldtest1 @127.0.0.1
# ;; global options: +cmd
# ;; Got answer:
# ;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 20034
# ;; flags: qr aa rd; QUERY: 1, ANSWER: 0, AUTHORITY: 4, ADDITIONAL: 1
# ;; WARNING: recursion requested but not available
#
# ;; OPT PSEUDOSECTION:
# ; EDNS: version: 0, flags: do; udp: 1232
# ;; QUESTION SECTION:
# ;tldtest1.			IN	A
#
# ;; AUTHORITY SECTION:
# tldtest1.		3600	IN	SOA	a.gtld-servers.net. nstld.verisign-grs.com. 2025053493 10800 3600 604800 3600
# tldtest1.		3600	IN	RRSIG	SOA 8 1 3600 20250515000000 20250424000000 14128 tldtest1. I8E5RB2yADcYJIORd6ZwgBKRiNW7kMcZqO/xA6gTHbOY/SVLgib6wVe5 ohEk6n7VFTHQKz+Yv1VV5yIwI+ctGE2er3lW/r+jPMZ0AGduTUF59s94 Bsz/5Zzzq6gZZTscOtezOBiKjO4V42h99hQxA4x3jIKYs/rO+ijaNmy4 c7A=
# tldtest1.		3600	IN	NSEC	domain1.tldtest1. SOA RRSIG NSEC DNSKEY CDNSKEY
# tldtest1.		3600	IN	RRSIG	NSEC 8 1 3600 20250515000000 20250424000000 14128 tldtest1. eld/e7tESam0faZJMyJRR8ldMqPmAkOOnhz3sLUUpfmY6KJKwQWVIBn1 xs0jMhmOXqzFWcukEGT9tDUUGlA5RyhY+ihorwMntu18sHewTKWjTFeb VyNkfze4nExBzCfrgbyl6O9W//68QDKIfB29yf1rOrPszdOwhR90Ko0o BzQ=
#
# ;; Query time: 4 msec
# ;; SERVER: 127.0.0.1#53(127.0.0.1)
# ;; WHEN: Wed May 07 09:42:22 EDT 2025
# ;; MSG SIZE  rcvd: 489

## DNSSEC ZSK key rotation (monthly)
#
#  The following commands are used to rotate the ZSK for a given zone.
#  https://doc.powerdns.com/authoritative/guides/zskroll.html

# Create an INACTIVE/PUBLISHED key that can begin propagating
./pdnsutil add-zone-key $ZONE zsk 1024 inactive published rsasha256

# Show the settings and make note of the key IDs for both the old and
# new ZSKs to be used later.
./pdnsutil show-zone $ZONE

# Validate with dig that the DNSKEY is available, making note of the TTL
# for this record. Need to wait for both old/new DNSKEY records to be shown
# on secondary DNS servers before proceeding.
dig DNSKEY $ZONE @127.0.0.1

# Change activation status for the two ZSKs. The key IDs are found in the
# previous output from show-zone. At this point, the new ZSK will be used
# for signing, but the old key is still available for verification.
./pdnsutil activate-zone-key $ZONE NEW-ZSK-ID
./pdnsutil deactivate-zone-key $ZONE OLD-ZSK-ID

# Wait for the longest TTL to ensure all secondaries have latest values,
# and then remove the old key.
./pdnsutil remove-zone-key $ZONE OLD-ZSK-ID

## DNSSEC KSK key rotation (annually)
#
#  The following commands are used to rotate the KSK for a given zone.
#  https://doc.powerdns.com/authoritative/guides/kskroll.html

# Create an ACTIVE/PUBLISHED key that can begin propagating
./pdnsutil add-zone-key $ZONE ksk 2048 active published rsasha256

# Show the settings and make note of the key IDs for both the old and
# new KSKs to be used later.
./pdnsutil show-zone $ZONE

# Validate with dig that the DNSKEY is available, making note of the TTL
# for this record. Need to wait for both old/new DNSKEY records to be shown
# on secondary DNS servers before proceeding.
dig DNSKEY $ZONE @127.0.0.1

# Now that both old/new KSK values are being used to sign (both are active),
# we can communicate with the gTLD registry to give them a new DS value to
# publish in the parent DNS zone. The output of DS or DNSKEY for the new KSK
# can be provided to the registry, depending on what format they need. Some
# registries may handle this automatically by reading our DNSKEY records, but
# need to verify on a per-registry basis if this is the case.
./pdnsutil show-zone $ZONE

# After propagation, the old key may be removed.
./pdnsutil remove-zone-key $ZONE OLD-KSK-ID

## AXFR Replication Setup on the Primary PowerDNS Server
#
#  The following commands are used to configure AXFR replication for a secondary
#  PowerDNS server.

# Assumes the TSIG was already created by PowerDnsWriter
./pdnsutil activate-tsig-key $ZONE axfr-key-$ZONE primary

## AXFR Replication Setup on the Secondary PowerDNS Server
#
#  The following commands are used to configure AXFR replication for a secondary
#  PowerDNS server.

./pdnsutil create-secondary-zone $ZONE $PRIMARY_IP:$PRIMARY_PORT
./pdnsutil import-tsig-key axfr-key-$ZONE hmac-sha256 $TSIG_SHARED_SECRET
./pdnsutil activate-tsig-key $ZONE axfr-key-$ZONE secondary
