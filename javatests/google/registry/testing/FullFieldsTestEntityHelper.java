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

package google.registry.testing;

import static google.registry.testing.DatastoreHelper.generateNewContactHostRoid;
import static google.registry.testing.DatastoreHelper.generateNewDomainRoid;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Ref;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.Idn;
import java.net.InetAddress;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Test helper methods for the rdap and whois packages. */
public final class FullFieldsTestEntityHelper {

  public static Registrar makeRegistrar(
      String clientId, String registrarName, Registrar.State state) {
    return makeRegistrar(clientId, registrarName, state, 1L);
  }

  public static Registrar makeRegistrar(
      String clientId, String registrarName, Registrar.State state, Long ianaIdentifier) {
    Registrar registrar = new Registrar.Builder()
      .setClientIdentifier(clientId)
      .setRegistrarName(registrarName)
      .setType(Registrar.Type.REAL)
      .setIanaIdentifier(ianaIdentifier)
      .setState(state)
      .setInternationalizedAddress(new RegistrarAddress.Builder()
          .setStreet(ImmutableList.of("123 Example Boulevard <script>"))
          .setCity("Williamsburg <script>")
          .setState("NY")
          .setZip("11211")
          .setCountryCode("US")
          .build())
      .setLocalizedAddress(new RegistrarAddress.Builder()
          .setStreet(ImmutableList.of("123 Example Boulevard <script>"))
          .setCity("Williamsburg <script>")
          .setState("NY")
          .setZip("11211")
          .setCountryCode("US")
          .build())
      .setPhoneNumber("+1.2125551212")
      .setFaxNumber("+1.2125551213")
      .setEmailAddress("contact-us@example.com")
      .setWhoisServer("whois.example.com")
      .setReferralUrl("http://www.example.com")
      .build();
    return registrar;
  }

  public static ImmutableList<RegistrarContact> makeRegistrarContacts(Registrar registrar) {
    return ImmutableList.of(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("johndoe@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
            // Purposely flip the internal/external admin/tech
            // distinction to make sure we're not relying on it.  Sigh.
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(true)
            .build(),
          new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Jane Doe")
            .setEmailAddress("janedoe@example.com")
            .setPhoneNumber("+1.2125551215")
            .setFaxNumber("+1.2125551216")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
            // Purposely flip the internal/external admin/tech
            // distinction to make sure we're not relying on it.  Sigh.
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
        .build());
  }

  public static HostResource makeHostResource(String fqhn, String ip) {
    return makeHostResource(fqhn, ip, null);
  }

  public static HostResource makeHostResource(
      String fqhn, @Nullable String ip1, @Nullable String ip2) {
    HostResource.Builder builder = new HostResource.Builder()
        .setRepoId(generateNewContactHostRoid())
        .setFullyQualifiedHostName(Idn.toASCII(fqhn))
        .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"));
    if ((ip1 != null) || (ip2 != null)) {
      ImmutableSet.Builder<InetAddress> ipBuilder = new ImmutableSet.Builder<>();
      if (ip1 != null) {
        ipBuilder.add(InetAddresses.forString(ip1));
      }
      if (ip2 != null) {
        ipBuilder.add(InetAddresses.forString(ip2));
      }
      builder.setInetAddresses(ipBuilder.build());
    }
    return builder.build();
  }

  public static HostResource makeAndPersistHostResource(
      String fqhn, @Nullable String ip, @Nullable DateTime creationTime) {
    return makeAndPersistHostResource(fqhn, ip, null, creationTime);
  }

  public static HostResource makeAndPersistHostResource(
      String fqhn, @Nullable String ip1, @Nullable String ip2, @Nullable DateTime creationTime) {
    HostResource hostResource = persistResource(makeHostResource(fqhn, ip1, ip2));
    if (creationTime != null) {
      persistResource(makeHistoryEntry(
          hostResource, HistoryEntry.Type.HOST_CREATE, null, "created", creationTime));
    }
    return hostResource;
  }

  public static ContactResource makeContactResource(
      String id, String name, @Nullable String email) {
    return makeContactResource(
        id, name, email, ImmutableList.of("123 Example Boulevard <script>"));
  }

  public static ContactResource makeContactResource(
      String id, String name, @Nullable String email, @Nullable List<String> street) {
    PostalInfo.Builder postalBuilder = new PostalInfo.Builder()
        .setType(PostalInfo.Type.INTERNATIONALIZED)
        .setName(name)
        .setOrg("GOOGLE INCORPORATED <script>");
    if (street != null) {
        postalBuilder.setAddress(new ContactAddress.Builder()
            .setStreet(ImmutableList.copyOf(street))
            .setCity("KOKOMO")
            .setState("BM")
            .setZip("31337")
            .setCountryCode("US")
            .build());
    }
    ContactResource.Builder builder = new ContactResource.Builder()
        .setContactId(id)
        .setRepoId(generateNewContactHostRoid())
        .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
        .setInternationalizedPostalInfo(postalBuilder.build())
        .setVoiceNumber(
            new ContactPhoneNumber.Builder()
            .setPhoneNumber("+1.2126660420")
            .build())
        .setFaxNumber(
            new ContactPhoneNumber.Builder()
            .setPhoneNumber("+1.2126660420")
            .build());
    if (email != null) {
      builder.setEmailAddress(email);
    }
    return builder.build();
  }

  public static ContactResource makeAndPersistContactResource(
      String id, String name, @Nullable String email, @Nullable DateTime creationTime) {
    return makeAndPersistContactResource(
        id, name, email, ImmutableList.of("123 Example Boulevard <script>"), creationTime);
  }

  public static ContactResource makeAndPersistContactResource(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable DateTime creationTime) {
    ContactResource contactResource = persistResource(makeContactResource(id, name, email, street));
    if (creationTime != null) {
      persistResource(makeHistoryEntry(
          contactResource, HistoryEntry.Type.CONTACT_CREATE, null, "created", creationTime));
    }
    return contactResource;
  }

  public static DomainResource makeDomainResource(
      String domain,
      @Nullable ContactResource registrant,
      @Nullable ContactResource admin,
      @Nullable ContactResource tech,
      @Nullable HostResource ns1,
      @Nullable HostResource ns2,
      Registrar registrar) {
    DomainResource.Builder builder = new DomainResource.Builder()
        .setFullyQualifiedDomainName(Idn.toASCII(domain))
        .setRepoId(generateNewDomainRoid(getTldFromDomainName(Idn.toASCII(domain))))
        .setLastEppUpdateTime(DateTime.parse("2009-05-29T20:13:00Z"))
        .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
        .setRegistrationExpirationTime(DateTime.parse("2110-10-08T00:44:59Z"))
        .setCurrentSponsorClientId(registrar.getClientIdentifier())
        .setStatusValues(ImmutableSet.of(
            StatusValue.CLIENT_DELETE_PROHIBITED,
            StatusValue.CLIENT_RENEW_PROHIBITED,
            StatusValue.CLIENT_TRANSFER_PROHIBITED,
            StatusValue.SERVER_UPDATE_PROHIBITED))
        .setDsData(ImmutableSet.of(new DelegationSignerData()));
    if (registrant != null) {
      builder.setRegistrant(Ref.create(registrant));
    }
    if ((admin != null) || (tech != null)) {
      ImmutableSet.Builder<DesignatedContact> contactsBuilder = new ImmutableSet.Builder<>();
      if (admin != null) {
        contactsBuilder.add(DesignatedContact.create(
            DesignatedContact.Type.ADMIN, Ref.create(admin)));
      }
      if (tech != null) {
        contactsBuilder.add(DesignatedContact.create(
            DesignatedContact.Type.TECH, Ref.create(tech)));
      }
      builder.setContacts(contactsBuilder.build());
    }
    if ((ns1 != null) || (ns2 != null)) {
      ImmutableSet.Builder<Ref<HostResource>> nsBuilder = new ImmutableSet.Builder<>();
      if (ns1 != null) {
        nsBuilder.add(Ref.create(ns1));
      }
      if (ns2 != null) {
        nsBuilder.add(Ref.create(ns2));
      }
      builder.setNameservers(nsBuilder.build());
    }
    return builder.build();
  }

  public static HistoryEntry makeHistoryEntry(
      EppResource resource,
      HistoryEntry.Type type,
      Period period,
      String reason,
      DateTime modificationTime) {
    HistoryEntry.Builder builder = new HistoryEntry.Builder()
        .setParent(resource)
        .setType(type)
        .setPeriod(period)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(modificationTime)
        .setClientId("foo")
        .setTrid(Trid.create("ABC-123"))
        .setBySuperuser(false)
        .setReason(reason)
        .setRequestedByRegistrar(false);
    return builder.build();
  }
}
