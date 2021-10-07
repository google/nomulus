// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.beam.initsql.Transforms.repairBadData;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;

import com.google.appengine.api.datastore.Entity;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Transforms}. */
public class TransformsTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testRepairBadData_canonicalizesDomainName() {
    DomainBase domain = newDomainBase("foobar.tld");
    Entity entity = ofyTm().transact(() -> auditedOfy().toEntity(domain));
    entity.setIndexedProperty("fullyQualifiedDomainName", "FOOBäR.TLD");
    assertThat(((DomainBase) auditedOfy().toPojo(repairBadData(entity))).getDomainName())
        .isEqualTo("xn--foobr-jra.tld");
  }

  @Test
  void testRepairBadData_canonicalizesHostName() {
    HostResource host = newHostResource("baz.foobar.tld");
    Entity entity = ofyTm().transact(() -> auditedOfy().toEntity(host));
    entity.setIndexedProperty(
        "fullyQualifiedHostName", "b̴̹͔͓̣̭̫͇͕̻̬̱͇͗͌́̆̋͒a̶̬̖͚̋̈́̽̇͝͠z̵͠.FOOBäR.TLD");
    assertThat(((HostResource) auditedOfy().toPojo(repairBadData(entity))).getHostName())
        .isEqualTo(
            "xn--baz-kdcb2ajgzb4jtg6doej4e6b9am7c7b6c5nd4k7gpa2a9a7dufyewec.xn--foobr-jra.tld");
  }
}
