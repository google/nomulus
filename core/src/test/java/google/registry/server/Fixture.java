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

package google.registry.server;

import static google.registry.model.domain.DesignatedContact.Type.ADMIN;
import static google.registry.model.domain.DesignatedContact.Type.BILLING;
import static google.registry.model.domain.DesignatedContact.Type.TECH;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.OteStatsTestHelper;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.ofy.Ofy;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.IOException;
import org.joda.time.DateTime;

/**
 * Datastore fixtures for the development webserver.
 *
 * <p><b>Warning:</b> These fixtures aren't really intended for unit tests, since they take upwards
 * of a second to load.
 */
public enum Fixture {

  INJECTED_FAKE_CLOCK {
    @Override
    public void load() {
      new InjectRule()
          .setStaticField(Ofy.class, "clock", new FakeClock(DateTime.parse("2000-01-01TZ")));
    }
  },

  /** Fixture of two TLDs, three contacts, two domains, and six hosts. */
  BASIC {
    @Override
    public void load() {
      createTlds("xn--q9jyb4c", "example");

      // Used for OT&E TLDs
      persistPremiumList("default_sandbox_list");

      try {
        OteStatsTestHelper.setupCompleteOte("otefinished");
        OteStatsTestHelper.setupIncompleteOte("oteunfinished");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      ContactResource google = persistResource(newContactResource("google")
          .asBuilder()
          .setLocalizedPostalInfo(new PostalInfo.Builder()
              .setType(PostalInfo.Type.LOCALIZED)
              .setName("Mr. Google")
              .setOrg("Google Inc.")
              .setAddress(new ContactAddress.Builder()
                  .setStreet(ImmutableList.of("111 8th Ave", "4th Floor"))
                  .setCity("New York")
                  .setState("NY")
                  .setZip("10011")
                  .setCountryCode("US")
                  .build())
              .build())
            .build());

      ContactResource justine = persistResource(newContactResource("justine")
          .asBuilder()
          .setLocalizedPostalInfo(new PostalInfo.Builder()
              .setType(PostalInfo.Type.LOCALIZED)
              .setName("Justine Bean")
              .setOrg("(✿◕ ‿◕ )ノ Incorporated")
              .setAddress(new ContactAddress.Builder()
                  .setStreet(ImmutableList.of("123 Fake St."))
                  .setCity("Stratford")
                  .setState("CT")
                  .setZip("06615")
                  .setCountryCode("US")
                  .build())
              .build())
            .build());

      ContactResource robert = persistResource(newContactResource("robert")
          .asBuilder()
          .setLocalizedPostalInfo(new PostalInfo.Builder()
              .setType(PostalInfo.Type.LOCALIZED)
              .setName("Captain Robert")
              .setOrg("Ancient World")
              .setAddress(new ContactAddress.Builder()
                  .setStreet(ImmutableList.of(
                      "A skeleton crew is what came back",
                      "And once in port he filled his sack",
                      "With bribes and cash and fame and coin"))
                  .setCity("Things to make a new crew join")
                  .setState("NY")
                  .setZip("10011")
                  .setCountryCode("US")
                  .build())
              .build())
            .build());

      persistResource(
          newDomainBase("love.xn--q9jyb4c", justine)
              .asBuilder()
              .setContacts(
                  ImmutableSet.of(
                      DesignatedContact.create(ADMIN, robert.createVKey()),
                      DesignatedContact.create(BILLING, google.createVKey()),
                      DesignatedContact.create(TECH, justine.createVKey())))
              .setNameservers(
                  ImmutableSet.of(
                      persistActiveHost("ns1.love.xn--q9jyb4c").createKey(),
                      persistActiveHost("ns2.love.xn--q9jyb4c").createKey()))
              .build());

      persistResource(
          newDomainBase("moogle.example", justine)
              .asBuilder()
              .setContacts(
                  ImmutableSet.of(
                      DesignatedContact.create(ADMIN, robert.createVKey()),
                      DesignatedContact.create(BILLING, google.createVKey()),
                      DesignatedContact.create(TECH, justine.createVKey())))
              .setNameservers(
                  ImmutableSet.of(
                      persistActiveHost("ns1.linode.com").createKey(),
                      persistActiveHost("ns2.linode.com").createKey(),
                      persistActiveHost("ns3.linode.com").createKey(),
                      persistActiveHost("ns4.linode.com").createKey(),
                      persistActiveHost("ns5.linode.com").createKey()))
              .build());

      persistResource(
          loadRegistrar("TheRegistrar")
              .asBuilder()
              .setAllowedTlds(ImmutableSet.of("example", "xn--q9jyb4c"))
              .build());
    }
  };

  /** Loads this fixture into Datastore. */
  public abstract void load();
}
