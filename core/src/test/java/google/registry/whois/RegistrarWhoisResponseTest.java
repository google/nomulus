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
import google.registry.model.registrar.RegistrarContact;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistrarWhoisResponse}. */
class RegistrarWhoisResponseTest {

  @RegisterExtension
  final AppEngineExtension gae = AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @Test
  void getTextOutputTest() {
    Registrar registrar =
        new Registrar.Builder()
            .setClientId("exregistrar")
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
    ImmutableList<RegistrarContact> contacts =
        ImmutableList.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Joe Registrar")
                .setEmailAddress("joeregistrar@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Doe")
                .setEmailAddress("johndoe@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jane Registrar")
                .setEmailAddress("janeregistrar@example-registrar.tld")
                .setPhoneNumber("+1.3105551214")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .setVisibleInWhoisAsAdmin(true)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jane Doe")
                .setEmailAddress("janedoe@example-registrar.tld")
                .setPhoneNumber("+1.1111111112")
                .setFaxNumber("+1.1111111112")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Bonnie & Clyde")
                .setEmailAddress("johngeek@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistSimpleResources(contacts);
    persistResource(registrar);

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
