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

package google.registry.beam.rde;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.beam.rde.RdePipeline.decodePendings;
import static google.registry.beam.rde.RdePipeline.encodePendings;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeResourceType.CONTACT;
import static google.registry.rde.RdeResourceType.DOMAIN;
import static google.registry.rde.RdeResourceType.HOST;
import static google.registry.rde.RdeResourceType.REGISTRAR;
import static google.registry.testing.AppEngineExtension.loadInitialData;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static org.joda.time.Duration.standardDays;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.TransactionManager;
import google.registry.rde.DepositFragment;
import google.registry.rde.PendingDeposit;
import google.registry.rde.RdeResourceType;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.values.KV;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdePipeline}. */
public class RdePipelineTest {

  private static final String REGISTRAR_NAME_PATTERN =
      "<rdeRegistrar:name>(.*)</rdeRegistrar:name>";

  private static final String DOMAIN_NAME_PATTERN = "<rdeDomain:name>(.*)</rdeDomain:name>";

  private static final String CONTACT_ID_PATTERN = "<rdeContact:id>(.*)</rdeContact:id>";

  private static final String HOST_NAME_PATTERN = "<rdeHost:name>(.*)</rdeHost:name>";

  private static final ImmutableSetMultimap<String, PendingDeposit> PENDINGS =
      ImmutableSetMultimap.of(
          "pal",
          PendingDeposit.create(
              "pal", DateTime.parse("2000-01-01TZ"), FULL, RDE_STAGING, standardDays(1)),
          "pal",
          PendingDeposit.create(
              "pal", DateTime.parse("2000-01-01TZ"), THIN, RDE_STAGING, standardDays(1)),
          "fun",
          PendingDeposit.create(
              "fun", DateTime.parse("2000-01-01TZ"), FULL, RDE_STAGING, standardDays(1)));

  // This is the default creation time for test data.
  private final FakeClock clock = new FakeClock(DateTime.parse("1999-12-31TZ"));

  // The pipeline runs in a different thread, which needs to be masqueraded as a GAE thread as well.
  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final DatastoreEntityExtension datastore = new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(clock).buildIntegrationTestRule();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private final RdePipelineOptions options =
      PipelineOptionsFactory.create().as(RdePipelineOptions.class);

  private RdePipeline rdePipeline;

  private TransactionManager originalTm;

  @BeforeEach
  void beforeEach() throws Exception {
    originalTm = tm();
    setTm(jpaTm());
    loadInitialData();

    // Two real registrars have been created by loadInitialData(), named "New Registrar" and "The
    // Registrar". Create one included registrar (external_monitoring) and two excluded ones.
    Registrar monitoringRegistrar =
        persistNewRegistrar("monitoring", "monitoring", Registrar.Type.MONITORING, null);
    Registrar testRegistrar = persistNewRegistrar("test", "test", Registrar.Type.TEST, null);
    Registrar externalMonitoringRegistrar =
        persistNewRegistrar(
            "externalmonitor", "external_monitoring", Registrar.Type.EXTERNAL_MONITORING, 9997L);
    // Set Registrar states which are required for reporting.
    tm().transact(
            () ->
                tm().putAll(
                        ImmutableList.of(
                            externalMonitoringRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            testRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            monitoringRegistrar.asBuilder().setState(State.ACTIVE).build())));

    createTld("pal");
    createTld("fun");
    createTld("cat");

    // Also persists a "contact1234" contact in the process.
    persistActiveDomain("hello.pal");
    persistActiveDomain("kitty.fun");
    // Should not appear anywhere.
    persistActiveDomain("lol.cat");
    persistDeletedDomain("deleted.pal", DateTime.parse("1999-12-30TZ"));

    persistActiveContact("contact456");

    HostResource host = persistEppResource(newHostResource("old.host.test"));
    // Set the clock to 2000-01-02, the updated host should NOT show up in RDE.
    clock.advanceBy(Duration.standardDays(2));
    persistEppResource(host.asBuilder().setHostName("new.host.test").build());

    options.setPendings(encodePendings(PENDINGS));
    // The EPP resources created in tests do not have all the fields populated, using a STRICT
    // validation mode will result in a lot of warnings during marshalling.
    options.setValidationMode("LENIENT");
    rdePipeline = new RdePipeline(options);
  }

  @AfterEach
  void afterEach() {
    setTm(originalTm);
  }

  @Test
  void testSuccess_encodeAndDecodePendingsMap() throws Exception {
    String encodedString = encodePendings(PENDINGS);
    assertThat(decodePendings(encodedString)).isEqualTo(PENDINGS);
  }

  @Test
  void testSuccess_createFragments() {
    PAssert.that(
            rdePipeline
                .createFragments(pipeline)
                .apply("Group by PendingDeposit", GroupByKey.create()))
        .satisfies(
            kvs -> {
              kvs.forEach(
                  kv -> {
                    // Registrar fragments.
                    assertThat(
                            getFragmentForType(kv, REGISTRAR)
                                .map(getXmlElement(REGISTRAR_NAME_PATTERN))
                                .collect(toImmutableSet()))
                        // The same registrars are attached to all the pending deposits.
                        .containsExactly("New Registrar", "The Registrar", "external_monitoring");
                    // Domain fragments.
                    assertThat(
                            getFragmentForType(kv, DOMAIN)
                                .map(getXmlElement(DOMAIN_NAME_PATTERN))
                                .anyMatch(
                                    domain ->
                                        // Deleted domain should not be included
                                        domain.equals("deleted.pal")
                                            // Only domains on the pending deposit's tld should
                                            // appear.
                                            || !kv.getKey()
                                                .tld()
                                                .equals(
                                                    Iterables.get(
                                                        Splitter.on('.').split(domain), 1))))
                        .isFalse();
                    if (kv.getKey().mode().equals(FULL)) {
                      // Contact fragments.
                      assertThat(
                              getFragmentForType(kv, CONTACT)
                                  .map(getXmlElement(CONTACT_ID_PATTERN))
                                  .collect(toImmutableSet()))
                          // The same contacts are attached too all pending deposits.
                          .containsExactly("contact1234", "contact456");
                      // Host fragments.
                      assertThat(
                              getFragmentForType(kv, HOST)
                                  .map(getXmlElement(HOST_NAME_PATTERN))
                                  .collect(toImmutableSet()))
                          // Should load the resource before update.
                          .containsExactly("old.host.test");
                    } else {
                      // BRDA does not contain contact or hosts.
                      assertThat(
                              Streams.stream(kv.getValue())
                                  .anyMatch(
                                      fragment ->
                                          fragment.type().equals(CONTACT)
                                              || fragment.type().equals(HOST)))
                          .isFalse();
                    }
                  });
              return null;
            });
    pipeline.run().waitUntilFinish();
  }

  private static Function<DepositFragment, String> getXmlElement(String pattern) {
    return (fragment) -> {
      Matcher matcher = Pattern.compile(pattern).matcher(fragment.xml());
      checkState(matcher.find(), "Missing %s in xml.", pattern);
      return matcher.group(1);
    };
  }

  private static Stream<DepositFragment> getFragmentForType(
      KV<PendingDeposit, Iterable<DepositFragment>> kv, RdeResourceType type) {
    return Streams.stream(kv.getValue()).filter(fragment -> fragment.type().equals(type));
  }
}
