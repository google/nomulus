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

package google.registry.model;

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadBytes;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry.Type;
import java.io.IOException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

public final class OteStatsTestHelper {

  public static void setupCompleteOte(String baseClientId) throws IOException {
    setupIncompleteOte(baseClientId);
    String oteAccount1 = String.format("%s-1", baseClientId);
    DateTime now = DateTime.now(DateTimeZone.UTC);
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("xn--abc-873b2e7eb1k8a4lpjvv.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(getBytes("domain_create_idn.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_RESTORE)
            .setXmlBytes(getBytes("domain_restore.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new HostHistory.Builder()
            .setHostRepoId(persistDeletedHost("ns1.example.tld", now).getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.HOST_DELETE)
            .setXmlBytes(getBytes("host_delete.xml"))
            .setModificationTime(now)
            .build());
  }

  /**
   * Sets up an incomplete OT&E registrar. It is missing the following entries:
   *
   * <ul>
   *   <li>DOMAIN_CREATES_IDN
   *   <li>DOMAIN_RESTORES
   *   <li>HOST_DELETES
   * </ul>
   *
   * <p>TODO(b/122830156): Have this replicate the exact OT&E workflow with the correct client IDs
   */
  public static void setupIncompleteOte(String baseClientId) throws IOException {
    createTld("tld");
    persistPremiumList("default_sandbox_list", USD, "sandbox,USD 1000");
    OteAccountBuilder.forClientId(baseClientId).addContact("email@example.com").buildAndPersist();
    String oteAccount1 = String.format("%s-1", baseClientId);
    DateTime now = DateTime.now(DateTimeZone.UTC);
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("exampleone.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(getBytes("domain_create_sunrise.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example-one.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(getBytes("domain_create_claim_notice.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(getBytes("domain_create_anchor_tenant_fee_standard.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(getBytes("domain_create_dsdata.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistDeletedDomain("example.tld", now).getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_DELETE)
            .setXmlBytes(getBytes("domain_delete.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_TRANSFER_APPROVE)
            .setXmlBytes(getBytes("domain_transfer_approve.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_TRANSFER_CANCEL)
            .setXmlBytes(getBytes("domain_transfer_cancel.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_TRANSFER_REJECT)
            .setXmlBytes(getBytes("domain_transfer_reject.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_TRANSFER_REQUEST)
            .setXmlBytes(getBytes("domain_transfer_request.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomainRepoId(persistActiveDomain("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.DOMAIN_UPDATE)
            .setXmlBytes(getBytes("domain_update_with_secdns.xml"))
            .setModificationTime(now)
            .build());
    persistResource(
        new HostHistory.Builder()
            .setHostRepoId(persistActiveHost("example.tld").getRepoId())
            .setClientId(oteAccount1)
            .setType(Type.HOST_CREATE)
            .setXmlBytes(getBytes("host_create_complete.xml"))
            .setModificationTime(now)
            .build());
    // Persist 10 host updates for a total of 25 history entries. Since these also sort last by
    // modification time, when these cause all tests to pass, only the first will be recorded and
    // the rest will be skipped.
    for (int i = 0; i < 10; i++) {
      persistResource(
          new HostHistory.Builder()
              .setHostRepoId(persistActiveHost("example.tld").getRepoId())
              .setClientId(oteAccount1)
              .setType(Type.HOST_UPDATE)
              .setXmlBytes(getBytes("host_update.xml"))
              .setTrid(Trid.create(null, String.format("blahtrid-%d", i)))
              .setModificationTime(END_OF_TIME)
              .build());
    }
  }

  private static byte[] getBytes(String filename) throws IOException {
    return loadBytes(OteStatsTestHelper.class, filename).read();
  }
}
