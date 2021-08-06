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

package google.registry.schema.replay;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DatastoreEntityExtension;
import java.util.stream.Stream;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for {@link SqlEntity#getPrimaryKeyString}. */
public class SqlEntityTest {

  @RegisterExtension static final JpaStaticExtension jpa = new JpaStaticExtension();

  private static ImmutableMap<SqlEntity, String> TEST_CASES;

  @BeforeAll
  static void setupTestCases() throws Exception {
    ImmutableMap.Builder<SqlEntity, String> testCaseBuilder = new ImmutableMap.Builder<>();
    Registry registry = DatabaseHelper.persistResource(DatabaseHelper.newRegistry("app", "TLD"));
    testCaseBuilder.put(registry, "_app");
    testCaseBuilder.put(DatabaseHelper.persistNewRegistrar("registrar1"), "_registrar1");
    ContactResource contact = DatabaseHelper.newContactResourceWithRoid("contact1", "CONTACT1");
    testCaseBuilder.put(contact, "_CONTACT1");
    DomainBase domain = DatabaseHelper.newDomainBase("abc.app", "DOMAIN1", contact);
    testCaseBuilder.put(domain, "_DOMAIN1");
    HostResource host = DatabaseHelper.newHostResourceWithRoid("host1", "HOST1");
    testCaseBuilder.put(host, "_HOST1");
    Spec11ThreatMatch spec11ThreatMatch =
        new Spec11ThreatMatch.Builder()
            .setDomainRepoId("ignored")
            .setDomainName("abc.app")
            .setRegistrarId("")
            .setCheckDate(LocalDate.now())
            .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
            .setId(11111L)
            .build();
    testCaseBuilder.put(spec11ThreatMatch, "_11111");

    TEST_CASES = testCaseBuilder.build();
  }

  @ParameterizedTest(name = "getPrimaryKeyString_{0}")
  @MethodSource("provideTestCases")
  void getPrimaryKeyString(String entityType, SqlEntity entity, String pattern) {
    assertThat(entity.getPrimaryKeyString()).contains(pattern);
  }

  private static Stream<Arguments> provideTestCases() {
    return TEST_CASES.entrySet().stream()
        .map(
            entry ->
                Arguments.of(
                    entry.getKey().getClass().getSimpleName(), entry.getKey(), entry.getValue()));
  }

  /** Adapts {@link JpaIntegrationTestExtension} for class level setup. */
  static class JpaStaticExtension implements AfterAllCallback, BeforeAllCallback {
    private final DatastoreEntityExtension entityExtension = new DatastoreEntityExtension();
    private final JpaIntegrationTestExtension jpa =
        new JpaTestRules.Builder().buildIntegrationTestRule();

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
      TransactionManagerFactory.removeTmOverrideForTest();
      jpa.afterEach(context);
      entityExtension.afterEach(context);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      entityExtension.beforeEach(context);
      jpa.beforeEach(context);
      TransactionManagerFactory.setTmForTest(TransactionManagerFactory.jpaTm());
    }
  }
}
