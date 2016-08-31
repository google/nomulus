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

package google.registry.model.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2_HASH;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.Registrar.Type;
import google.registry.testing.ExceptionRule;
import google.registry.util.CidrAddressBlock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link Registrar}. */
public class RegistrarTest extends EntityTestCase {

  @Rule
  public ExceptionRule thrown = new ExceptionRule();

  Registrar registrar;

  @Before
  public void setUp() throws Exception {
    createTld("xn--q9jyb4c");
    // Set up a new persisted registrar entity.
    registrar = cloneAndSetAutoTimestamps(
        new Registrar.Builder()
            .setClientIdentifier("registrar")
            .setRegistrarName("full registrar name")
            .setType(Type.REAL)
            .setState(State.PENDING)
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .setWhoisServer("whois.example.com")
            .setBlockPremiumNames(true)
            .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
            .setIpAddressWhitelist(ImmutableList.of(
                CidrAddressBlock.create("192.168.1.1/31"),
                CidrAddressBlock.create("10.0.0.1/8")))
            .setPassword("foobar")
            .setInternationalizedAddress(new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example Boulevard"))
                .setCity("Williamsburg")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build())
            .setLocalizedAddress(new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example Boulevard."))
                .setCity("Williamsburg")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build())
            .setPhoneNumber("+1.2125551212")
            .setFaxNumber("+1.2125551213")
            .setEmailAddress("contact-us@example.com")
            .setUrl("http://www.example.com")
            .setReferralUrl("http://www.example.com")
            .setIcannReferralEmail("foo@example.com")
            .setDriveFolderId("drive folder id")
            .setIanaIdentifier(8L)
            .setBillingIdentifier(5325L)
            .setPhonePasscode("01234")
            .build());
    persistResource(registrar);
    persistSimpleResources(ImmutableList.of(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Abused")
            .setEmailAddress("johnabuse@example.com")
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(
                RegistrarContact.Type.ABUSE,
                RegistrarContact.Type.ADMIN))
            .build(),
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("johndoe@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(
                RegistrarContact.Type.LEGAL,
                RegistrarContact.Type.MARKETING))
            .build()));
  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(registrar).isEqualTo(ofy().load().type(Registrar.class)
        .parent(EntityGroupRoot.getCrossTldKey())
        .id(registrar.clientIdentifier)
        .now());
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(registrar, "registrarName", "ianaIdentifier");
  }

  @Test
  public void testFailure_passwordNull() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Password must be [6,16] characters long.");
    new Registrar.Builder().setPassword(null);
  }

  @Test
  public void testFailure_passwordTooShort() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Password must be [6,16] characters long.");
    new Registrar.Builder().setPassword("abcde");
  }

  @Test
  public void testFailure_passwordTooLong() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Password must be [6,16] characters long.");
    new Registrar.Builder().setPassword("abcdefghijklmnopq");
  }

  @Test
  public void testSuccess_clientIdentifierBounds() throws Exception {
    registrar = registrar.asBuilder().setClientIdentifier("abc").build();
    assertThat(registrar.getClientIdentifier()).isEqualTo("abc");
    registrar = registrar.asBuilder().setClientIdentifier("abcdefghijklmnop").build();
    assertThat(registrar.getClientIdentifier()).isEqualTo("abcdefghijklmnop");
  }

  @Test
  public void testFailure_clientIdentifierTooShort() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setClientIdentifier("ab");
  }

  @Test
  public void testFailure_clientIdentifierTooLong() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setClientIdentifier("abcdefghijklmnopq");
  }

  @Test
  public void testSetCertificateHash_alsoSetsHash() throws Exception {
    registrar = registrar.asBuilder().setClientCertificate(null, clock.nowUtc()).build();
    clock.advanceOneMilli();
    registrar = registrar.asBuilder()
        .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
        .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(clock.nowUtc());
    assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  public void testDeleteCertificateHash_alsoDeletesHash() throws Exception {
    assertThat(registrar.getClientCertificateHash()).isNotNull();
    clock.advanceOneMilli();
    registrar = registrar.asBuilder()
        .setClientCertificate(null, clock.nowUtc())
        .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(clock.nowUtc());
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
  }

  @Test
  public void testSetFailoverCertificateHash_alsoSetsHash() throws Exception {
    clock.advanceOneMilli();
    registrar = registrar.asBuilder()
        .setFailoverClientCertificate(SAMPLE_CERT2, clock.nowUtc())
        .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(clock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT2);
    assertThat(registrar.getFailoverClientCertificateHash()).isEqualTo(SAMPLE_CERT2_HASH);
  }

  @Test
  public void testDeleteFailoverCertificateHash_alsoDeletesHash() throws Exception {
    registrar = registrar.asBuilder()
        .setFailoverClientCertificate(SAMPLE_CERT, clock.nowUtc())
        .build();
    assertThat(registrar.getFailoverClientCertificateHash()).isNotNull();
    clock.advanceOneMilli();
    registrar = registrar.asBuilder()
        .setFailoverClientCertificate(null, clock.nowUtc())
        .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(clock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    assertThat(registrar.getFailoverClientCertificateHash()).isNull();
  }

  @Test
  public void testSuccess_clearingIanaAndBillingIds() throws Exception {
    registrar.asBuilder()
        .setType(Type.TEST)
        .setIanaIdentifier(null)
        .setBillingIdentifier(null)
        .build();
  }

  @Test
  public void testSuccess_ianaIdForInternal() throws Exception {
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9998L).build();
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9999L).build();
  }

  @Test
  public void testSuccess_ianaIdForPdt() throws Exception {
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9995L).build();
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9996L).build();
  }

  @Test
  public void testSuccess_ianaIdForExternalMonitoring() throws Exception {
    registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(9997L).build();
  }

  @Test
  public void testSuccess_emptyContactTypesAllowed() throws Exception {
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Abused")
            .setEmailAddress("johnabuse@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            // No setTypes(...)
            .build());
    for (RegistrarContact rc : registrar.getContacts()) {
      rc.toJsonMap();
    }
  }

  @Test
  public void testFailure_missingRegistrarType() throws Exception {
    thrown.expect(NullPointerException.class);
    new Registrar.Builder().build();
  }

  @Test
  public void testFailure_badIanaIdForInternal() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.INTERNAL).setIanaIdentifier(8L).build();
  }

  @Test
  public void testFailure_badIanaIdForPdt() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.PDT).setIanaIdentifier(8L).build();
  }

  @Test
  public void testFailure_badIanaIdForExternalMonitoring() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(8L).build();
  }

  @Test
  public void testFailure_missingIanaIdForReal() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.REAL).build();
  }

  @Test
  public void testFailure_missingIanaIdForInternal() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.INTERNAL).build();
  }

  @Test
  public void testFailure_missingIanaIdForPdt() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.PDT).build();
  }

  @Test
  public void testFailure_missingIanaIdForExternalMonitoring() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setType(Type.EXTERNAL_MONITORING).build();
  }

  @Test
  public void testFailure_phonePasscodeTooShort() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setPhonePasscode("0123");
  }

  @Test
  public void testFailure_phonePasscodeTooLong() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setPhonePasscode("012345");
  }

  @Test
  public void testFailure_phonePasscodeInvalidCharacters() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    new Registrar.Builder().setPhonePasscode("code1");
  }
}
