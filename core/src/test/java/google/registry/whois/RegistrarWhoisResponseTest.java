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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.whois.WhoisTestData.loadFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPocBase;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistrarWhoisResponse}. */
class RegistrarWhoisResponseTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @Test
  void getTextOutputTest() {
    Registrar registrar =
        new Registrar.Builder()
            .setRegistrarId("exregistrar")
            .setRegistrarName("Example Registrar, Inc.")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("1234 Admiralty Way"))
                    .setCity("Marina del Rey")
                    .setState("CA")
                    .setZip("90292")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.3105551212")
            .setFaxNumber("+1.3105551213")
            .setEmailAddress("registrar@example.tld")
            .setWhoisServer("whois.example-registrar.tld")
            .setUrl("http://my.fake.url")
            .build();
    // Use the registrar key for contacts' parent.
    ImmutableList<RegistrarPoc> contacts =
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Joe Registrar")
                .setEmailAddress("joeregistrar@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarPocBase.Type.ADMIN))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Doe")
                .setEmailAddress("johndoe@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarPocBase.Type.ADMIN))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jane Registrar")
                .setEmailAddress("janeregistrar@example-registrar.tld")
                .setPhoneNumber("+1.3105551214")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarPocBase.Type.ADMIN))
                .setVisibleInWhoisAsAdmin(true)
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jane Doe")
                .setEmailAddress("janedoe@example-registrar.tld")
                .setPhoneNumber("+1.1111111112")
                .setFaxNumber("+1.1111111112")
                .setTypes(ImmutableSet.of(RegistrarPocBase.Type.TECH))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Bonnie & Clyde")
                .setEmailAddress("johngeek@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarPocBase.Type.TECH))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistResource(registrar);
    persistSimpleResources(contacts);

    RegistrarWhoisResponse registrarWhoisResponse =
        new RegistrarWhoisResponse(registrar, clock.nowUtc());
    assertThat(
            registrarWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_registrar.txt"), 1));
  }

  @Test
  void testSetOfFields() {
    Registrar registrar =
        persistNewRegistrar("exregistrar", "Ex-Registrar", Registrar.Type.REAL, 8L);

    RegistrarWhoisResponse registrarWhoisResponse =
        new RegistrarWhoisResponse(registrar, clock.nowUtc());
    // Just make sure this doesn't NPE.
    registrarWhoisResponse.getResponse(
        false, "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested.");
  }
}
