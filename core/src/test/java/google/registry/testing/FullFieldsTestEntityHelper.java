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

package google.registry.testing;

import static google.registry.testing.DatabaseHelper.generateNewContactHostRoid;
import static google.registry.testing.DatabaseHelper.generateNewDomainRoid;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.EppResource;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.Idn;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Test helper methods for the rdap and whois packages. */
public final class FullFieldsTestEntityHelper {

  public static Registrar makeRegistrar(
      String registrarId, String registrarName, Registrar.State state) {
    return makeRegistrar(registrarId, registrarName, state, 1L);
  }

  public static Registrar makeRegistrar(
      String registrarId, String registrarName, Registrar.State state, Long ianaIdentifier) {
    return new Registrar.Builder()
        .setRegistrarId(registrarId)
        .setRegistrarName(registrarName)
        .setType(Registrar.Type.REAL)
        .setIanaIdentifier(ianaIdentifier)
        .setState(state)
        .setInternationalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example Boulevard <script>"))
                .setCity("Williamsburg <script>")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build())
        .setLocalizedAddress(
            new RegistrarAddress.Builder()
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
        .setRdapBaseUrls(
            ImmutableSet.of(
                "https://rdap.example.com/withSlash/", "https://rdap.example.com/withoutSlash"))
        .setUrl("http://my.fake.url")
        .build();
  }

  public static ImmutableList<RegistrarPoc> makeRegistrarPocs(Registrar registrar) {
    return ImmutableList.of(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("johndoe@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
            // Purposely flip the internal/external admin/tech
            // distinction to make sure we're not relying on it.  Sigh.
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(true)
            .build(),
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Jane Doe")
            .setEmailAddress("janedoe@example.com")
            .setPhoneNumber("+1.2125551215")
            .setFaxNumber("+1.2125551216")
            .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
            // Purposely flip the internal/external admin/tech
            // distinction to make sure we're not relying on it.  Sigh.
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .build(),
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Jake Doe")
            .setEmailAddress("jakedoe@example.com")
            .setPhoneNumber("+1.2125551216")
            .setFaxNumber("+1.2125551216")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
  }

  public static Host makeHost(String fqhn, String ip) {
    return makeHost(fqhn, ip, null);
  }

  public static Host makeHost(String fqhn, @Nullable String ip1, @Nullable String ip2) {
    return makeHost(fqhn, ip1, ip2, "TheRegistrar");
  }

  public static Host makeHost(
      String fqhn, @Nullable String ip1, @Nullable String ip2, String registrarClientId) {
    Host.Builder builder =
        new Host.Builder()
            .setRepoId(generateNewContactHostRoid())
            .setHostName(Idn.toASCII(fqhn))
            .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
            .setPersistedCurrentSponsorRegistrarId(registrarClientId);
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

  public static Host makeAndPersistHost(
      String fqhn, @Nullable String ip, @Nullable DateTime creationTime) {
    return makeAndPersistHost(fqhn, ip, null, creationTime);
  }

  public static Host makeAndPersistHost(
      String fqhn, @Nullable String ip1, @Nullable String ip2, @Nullable DateTime creationTime) {
    return makeAndPersistHost(fqhn, ip1, ip2, creationTime, "TheRegistrar");
  }

  public static Host makeAndPersistHost(
      String fqhn,
      @Nullable String ip1,
      @Nullable String ip2,
      @Nullable DateTime creationTime,
      String registrarClientId) {
    Host host = persistResource(makeHost(fqhn, ip1, ip2, registrarClientId));
    if (creationTime != null) {
      persistResource(
          makeHistoryEntry(host, HistoryEntry.Type.HOST_CREATE, null, "created", creationTime));
    }
    return host;
  }

  public static Contact makeContact(String id, String name, @Nullable String email) {
    return makeContact(id, name, email, ImmutableList.of("123 Example Boulevard <script>"), null);
  }

  public static Contact makeContact(
      String id, String name, @Nullable String email, @Nullable Registrar registrar) {
    return makeContact(
        id, name, email, ImmutableList.of("123 Example Boulevard <script>"), registrar);
  }

  public static Contact makeContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable Registrar registrar) {
    return makeContact(id, name, email, street, registrar, null);
  }

  public static Contact makeContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable Registrar registrar,
      @Nullable DateTime deletionTime) {
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
    Contact.Builder builder =
        new Contact.Builder()
            .setContactId(id)
            .setRepoId(generateNewContactHostRoid())
            .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
            .setInternationalizedPostalInfo(postalBuilder.build())
            .setVoiceNumber(
                new ContactPhoneNumber.Builder().setPhoneNumber("+1.2126660420").build())
            .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("+1.2126660420").build());
    if (email != null) {
      builder.setEmailAddress(email);
    }
    String registrarId = registrar == null ? "TheRegistrar" : registrar.getRegistrarId();
    builder.setCreationRegistrarId(registrarId).setPersistedCurrentSponsorRegistrarId(registrarId);
    if (deletionTime != null) {
      builder.setDeletionTime(deletionTime);
    }
    return builder.build();
  }

  public static Contact makeWipedOutContact(
      String id, @Nullable Registrar registrar, @Nullable DateTime deletionTime) {
    Contact.Builder builder =
        new Contact.Builder()
            .setContactId(id)
            .setRepoId(generateNewContactHostRoid())
            .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"));
    if (registrar != null) {
      builder
          .setCreationRegistrarId(registrar.getRegistrarId())
          .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId());
    }
    if (deletionTime != null) {
      builder.setDeletionTime(deletionTime);
    }
    return builder.build();
  }

  public static Contact makeAndPersistContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable DateTime creationTime,
      @Nullable Registrar registrar) {
    return makeAndPersistContact(
        id,
        name,
        email,
        ImmutableList.of("123 Example Boulevard <script>"),
        creationTime,
        registrar,
        null);
  }

  public static Contact makeAndPersistContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable DateTime creationTime) {
    return makeAndPersistContact(id, name, email, street, creationTime, null, null);
  }

  public static Contact makeAndPersistContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable DateTime creationTime,
      @Nullable Registrar registrar) {
    return makeAndPersistContact(id, name, email, street, creationTime, registrar, null);
  }

  public static Contact makeAndPersistContact(
      String id,
      String name,
      @Nullable String email,
      @Nullable List<String> street,
      @Nullable DateTime creationTime,
      @Nullable Registrar registrar,
      @Nullable DateTime deletionTime) {
    Contact contact =
        persistResource(makeContact(id, name, email, street, registrar, deletionTime));
    if (creationTime != null) {
      persistResource(
          makeHistoryEntry(
              contact, HistoryEntry.Type.CONTACT_CREATE, null, "created", creationTime));
    }
    if (deletionTime != null) {
      persistResource(
          makeHistoryEntry(
              contact, HistoryEntry.Type.CONTACT_DELETE, null, "deleted", deletionTime));
    }
    return contact;
  }

  public static Contact makeAndPersistDeletedContact(
      String id, DateTime creationTime, Registrar registrar, DateTime deletionTime) {
    Contact contact = persistResource(makeWipedOutContact(id, registrar, deletionTime));
    persistResource(
        makeHistoryEntry(contact, HistoryEntry.Type.CONTACT_CREATE, null, "created", creationTime));
    persistResource(
        makeHistoryEntry(contact, HistoryEntry.Type.CONTACT_DELETE, null, "deleted", deletionTime));
    return contact;
  }

  public static Domain makeDomain(
      String domain,
      @Nullable Contact registrant,
      @Nullable Contact admin,
      @Nullable Contact tech,
      @Nullable Host ns1,
      @Nullable Host ns2,
      Registrar registrar) {
    Domain.Builder builder =
        new Domain.Builder()
            .setDomainName(Idn.toASCII(domain))
            .setRepoId(generateNewDomainRoid(getTldFromDomainName(Idn.toASCII(domain))))
            .setLastEppUpdateTime(DateTime.parse("2009-05-29T20:13:00Z"))
            .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
            .setRegistrationExpirationTime(DateTime.parse("2110-10-08T00:44:59Z"))
            .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId())
            .setCreationRegistrarId(registrar.getRegistrarId())
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.CLIENT_RENEW_PROHIBITED,
                    StatusValue.CLIENT_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED))
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, "deadface")));
    if (registrant != null) {
      builder.setRegistrant(Optional.of(registrant.createVKey()));
    }
    if ((admin != null) || (tech != null)) {
      ImmutableSet.Builder<DesignatedContact> contactsBuilder = new ImmutableSet.Builder<>();
      if (admin != null) {
        contactsBuilder.add(
            DesignatedContact.create(DesignatedContact.Type.ADMIN, admin.createVKey()));
      }
      if (tech != null) {
        contactsBuilder.add(
            DesignatedContact.create(DesignatedContact.Type.TECH, tech.createVKey()));
      }
      builder.setContacts(contactsBuilder.build());
    }
    if ((ns1 != null) || (ns2 != null)) {
      ImmutableSet.Builder<VKey<Host>> nsBuilder = new ImmutableSet.Builder<>();
      if (ns1 != null) {
        nsBuilder.add(ns1.createVKey());
      }
      if (ns2 != null) {
        nsBuilder.add(ns2.createVKey());
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
    HistoryEntry.Builder<?, ?> builder =
        HistoryEntry.createBuilderForResource(resource)
            .setType(type)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(modificationTime)
            .setRegistrarId(resource.getPersistedCurrentSponsorRegistrarId())
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason(reason)
            .setRequestedByRegistrar(false);
    if (builder instanceof DomainHistory.Builder) {
      ((DomainHistory.Builder) builder).setPeriod(period);
    }
    return builder.build();
  }
}
