// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.reporting.Spec11ThreatMatch.ThreatType.MALWARE;
import static google.registry.model.reporting.Spec11ThreatMatch.ThreatType.UNWANTED_SOFTWARE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Spec11ThreatMatch}. */
public final class Spec11ThreatMatchTest extends EntityTestCase {

  private static final String REGISTRAR_ID = "registrar";
  private static final LocalDate DATE = LocalDate.parse("2020-06-10", ISODateTimeFormat.date());

  private Spec11ThreatMatch threat;
  private Domain domain;
  private Host host;

  Spec11ThreatMatchTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    VKey<Host> hostVKey = VKey.create(Host.class, "host");
    String domainRepoId = "4-TLD";
    createTld("tld");

    // Create a domain for the purpose of testing a foreign key reference in the Threat table.
    domain =
        new Domain()
            .asBuilder()
            .setCreationRegistrarId(REGISTRAR_ID)
            .setPersistedCurrentSponsorRegistrarId(REGISTRAR_ID)
            .setDomainName("foo.tld")
            .setRepoId(domainRepoId)
            .setNameservers(hostVKey)
            .build();

    // Create a host for the purpose of testing a foreign key reference in the Domain table. */
    host =
        new Host.Builder()
            .setRepoId("host")
            .setHostName("ns1.example.com")
            .setCreationRegistrarId(REGISTRAR_ID)
            .setPersistedCurrentSponsorRegistrarId(REGISTRAR_ID)
            .build();

    threat =
        new Spec11ThreatMatch.Builder()
            .setThreatTypes(ImmutableSet.of(MALWARE, UNWANTED_SOFTWARE))
            .setCheckDate(DATE)
            .setDomainName("foo.tld")
            .setDomainRepoId(domainRepoId)
            .setRegistrarId(REGISTRAR_ID)
            .build();
  }

  @Test
  void testPersistence() {
    createTld("tld");
    saveRegistrar(REGISTRAR_ID);
    tm().transact(() -> tm().insertAll(domain, host, threat));
    assertAboutImmutableObjects().that(loadByEntity(threat)).isEqualExceptFields(threat, "id");
  }

  @Test
  @Disabled("We can't rely on foreign keys until we've migrated to SQL")
  void testThreatForeignKeyConstraints() {
    // Persist the threat without the associated registrar.
    assertThrowForeignKeyViolation(() -> persistResources(host, domain, threat));

    saveRegistrar(REGISTRAR_ID);

    // Persist the threat without the associated domain.
    assertThrowForeignKeyViolation(() -> persistResources(host, threat));
  }

  @Test
  void testFailure_threatsWithInvalidFields() {
    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setRegistrarId(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainName(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setCheckDate(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainRepoId(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setThreatTypes(ImmutableSet.of()));

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setThreatTypes(null).build());
  }
}
