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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.io.Files.asCharSink;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.insertSimpleResources;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.walk;
import static java.util.Comparator.reverseOrder;
import static org.json.XML.toJSONObject;

import com.google.appengine.tools.development.testing.LocalModulesServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig;
import com.google.appengine.tools.development.testing.LocalURLFetchServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalUserServiceTestConfig;
import com.google.apphosting.api.ApiProxy;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.util.Clock;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit extension for managing the App Engine testing environment.
 *
 * <p>Generally you'll want to configure the environment using only the services you need (because
 * each service is expensive to create).
 *
 * <p>This extension also resets global Objectify for the current thread.
 */
public final class AppEngineExtension implements BeforeEachCallback, AfterEachCallback {

  /**
   * The GAE testing library requires queue.xml to be a file, not a resource in a jar, so we read it
   * in here and write it to a temporary file later.
   */
  private static final String QUEUE_XML =
      readResourceUtf8("google/registry/env/common/default/WEB-INF/queue.xml");

  /** A parsed version of the indexes used in the prod code. */
  private static final Set<String> MANUAL_INDEXES =
      getIndexXmlStrings(
          readResourceUtf8("google/registry/env/common/default/WEB-INF/datastore-indexes.xml"));

  private static final String LOGGING_PROPERTIES =
      readResourceUtf8(AppEngineExtension.class, "logging.properties");

  private LocalServiceTestHelper helper;

  /**
   * A temporary directory for AppEngineExtension's internal temp files that is different for each
   * test.
   *
   * <p>Note that we can't use {@link TempDir} here because that only works in test classes, not
   * extensions.
   */
  File tmpDir;

  /**
   * Sets up a SQL database. This is for test classes that are not a member of the {@code
   * SqlIntegrationTestSuite}.
   */
  private JpaIntegrationTestExtension jpaIntegrationTestExtension = null;

  /**
   * Sets up a SQL database and records the JPA entities tested by each test class. This is for
   * {@code SqlIntegrationTestSuite} members.
   */
  private JpaIntegrationWithCoverageExtension jpaIntegrationWithCoverageExtension = null;

  private JpaUnitTestExtension jpaUnitTestExtension;

  private boolean withoutCannedData;
  private boolean withCloudSql;
  private boolean enableJpaEntityCoverageCheck;
  private boolean withJpaUnitTest;
  private boolean withLocalModules;
  private boolean withTaskQueue;
  private boolean withUserService;
  private boolean withUrlFetch;
  private Clock clock;

  private String taskQueueXml;
  private UserInfo userInfo;

  // Test Objectify entity classes to be used with this AppEngineExtension instance.
  private ImmutableList<Class<?>> jpaTestEntities;

  public Optional<JpaIntegrationTestExtension> getJpaIntegrationTestExtension() {
    return Optional.ofNullable(jpaIntegrationTestExtension);
  }

  /** Builder for {@link AppEngineExtension}. */
  public static class Builder {

    private AppEngineExtension extension = new AppEngineExtension();
    private ImmutableList.Builder<Class<?>> jpaTestEntities = new ImmutableList.Builder<>();

    /** Turns on Cloud SQL only, for use by test data generators. */
    public Builder withCloudSql() {
      extension.withCloudSql = true;
      return this;
    }

    /** Disables insertion of canned data. */
    public Builder withoutCannedData() {
      extension.withoutCannedData = true;
      return this;
    }

    /**
     * Enables JPA entity coverage check if {@code enabled} is true. This should only be enabled for
     * members of SqlIntegrationTestSuite.
     */
    public Builder enableJpaEntityCoverageCheck(boolean enabled) {
      extension.enableJpaEntityCoverageCheck = enabled;
      return this;
    }

    /** Turn on the use of local modules. */
    public Builder withLocalModules() {
      extension.withLocalModules = true;
      return this;
    }

    /** Turn on the task queue service. */
    public Builder withTaskQueue() {
      return withTaskQueue(QUEUE_XML);
    }

    /** Turn on the task queue service with a specified set of queues. */
    public Builder withTaskQueue(String taskQueueXml) {
      extension.withTaskQueue = true;
      extension.taskQueueXml = taskQueueXml;
      return this;
    }

    /** Turn on the URL Fetch service. */
    public Builder withUrlFetch() {
      extension.withUrlFetch = true;
      return this;
    }

    public Builder withClock(Clock clock) {
      extension.clock = clock;
      return this;
    }

    public Builder withUserService(UserInfo userInfo) {
      extension.withUserService = true;
      extension.userInfo = userInfo;
      return this;
    }

    public Builder withJpaUnitTestEntities(Class<?>... entities) {
      jpaTestEntities.add(entities);
      extension.withJpaUnitTest = true;
      return this;
    }

    public AppEngineExtension build() {
      checkState(
          !extension.enableJpaEntityCoverageCheck || extension.withCloudSql,
          "withJpaEntityCoverageCheck enabled without Cloud SQL");
      checkState(
          !extension.withJpaUnitTest || extension.withCloudSql,
          "withJpaUnitTestEntities enabled without Cloud SQL");
      checkState(
          !extension.withJpaUnitTest || !extension.enableJpaEntityCoverageCheck,
          "withJpaUnitTestEntities cannot be set when enableJpaEntityCoverageCheck");
      extension.jpaTestEntities = this.jpaTestEntities.build();
      return extension;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private static Registrar.Builder makeRegistrarCommon() {
    return new Registrar.Builder()
        .setType(Registrar.Type.REAL)
        .setState(State.ACTIVE)
        .setIcannReferralEmail("lol@sloth.test")
        .setUrl("http://my.fake.url")
        .setInternationalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example Boulevard"))
                .setCity("Williamsburg")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build())
        .setLocalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example B\u0151ulevard"))
                .setCity("Williamsburg")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build())
        .setPhoneNumber("+1.3334445555")
        .setPhonePasscode("12345")
        .setBillingAccountMap(ImmutableMap.of(CurrencyUnit.USD, "abc123"))
        .setContactsRequireSyncing(true);
  }

  /** Public factory for first Registrar to allow comparison against stored value in unit tests. */
  public static Registrar makeRegistrar1() {
    return makeRegistrarCommon()
        .setRegistrarId("NewRegistrar")
        .setRegistrarName("New Registrar")
        .setEmailAddress("new.registrar@example.com")
        .setIanaIdentifier(8L)
        .setPassword("foo-BAR2")
        .setPhoneNumber("+1.3334445555")
        .setPhonePasscode("12345")
        .setRegistryLockAllowed(false)
        .build();
  }

  /** Public factory for second Registrar to allow comparison against stored value in unit tests. */
  public static Registrar makeRegistrar2() {
    return makeRegistrarCommon()
        .setRegistrarId("TheRegistrar")
        .setRegistrarName("The Registrar")
        .setEmailAddress("the.registrar@example.com")
        .setIanaIdentifier(1L)
        .setPassword("password2")
        .setPhoneNumber("+1.2223334444")
        .setPhonePasscode("22222")
        .setRegistryLockAllowed(true)
        .build();
  }

  /**
   * Public factory for first RegistrarContact to allow comparison against stored value in unit
   * tests.
   */
  public static RegistrarPoc makeRegistrarContact1() {
    return new RegistrarPoc.Builder()
        .setRegistrar(makeRegistrar1())
        .setName("Jane Doe")
        .setVisibleInWhoisAsAdmin(true)
        .setVisibleInWhoisAsTech(false)
        .setEmailAddress("janedoe@theregistrar.com")
        .setPhoneNumber("+1.1234567890")
        .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
        .build();
  }

  /**
   * Public factory for second RegistrarContact to allow comparison against stored value in unit
   * tests.
   */
  public static RegistrarPoc makeRegistrarContact2() {
    return new RegistrarPoc.Builder()
        .setRegistrar(makeRegistrar2())
        .setName("John Doe")
        .setEmailAddress("johndoe@theregistrar.com")
        .setPhoneNumber("+1.1234567890")
        .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
        .setLoginEmailAddress("johndoe@theregistrar.com")
        .build();
  }

  public static RegistrarPoc makeRegistrarContact3() {
    return new RegistrarPoc.Builder()
        .setRegistrar(makeRegistrar2())
        .setName("Marla Singer")
        .setEmailAddress("Marla.Singer@crr.com")
        .setRegistryLockEmailAddress("Marla.Singer.RegistryLock@crr.com")
        .setPhoneNumber("+1.2128675309")
        .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
        .setLoginEmailAddress("Marla.Singer@crr.com")
        .setAllowedToSetRegistryLockPassword(true)
        .setRegistryLockPassword("hi")
        .build();
  }

  /** Called before every test method. */
  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    checkArgumentNotNull(context, "The ExtensionContext must not be null");
    setUp();
    if (withCloudSql) {
      JpaTestExtensions.Builder builder =
          new JpaTestExtensions.Builder().withEntityClass(jpaTestEntities.toArray(new Class[0]));
      if (withoutCannedData) {
        builder.withoutCannedData();
      }
      if (clock != null) {
        builder.withClock(clock);
      }
      if (enableJpaEntityCoverageCheck) {
        jpaIntegrationWithCoverageExtension = builder.buildIntegrationWithCoverageExtension();
        jpaIntegrationWithCoverageExtension.beforeEach(context);
      } else if (withJpaUnitTest) {
        jpaUnitTestExtension = builder.buildUnitTestExtension();
        jpaUnitTestExtension.beforeEach(context);
      } else {
        jpaIntegrationTestExtension = builder.buildIntegrationTestExtension();
        jpaIntegrationTestExtension.beforeEach(context);
      }

      // Reset SQL Sequence based id allocation so that ids are deterministic in tests.
      tm().transact(
              () ->
                  tm().getEntityManager()
                      .createNativeQuery(
                          "alter sequence if exists project_wide_unique_id_seq start 1 minvalue 1"
                              + " restart with 1")
                      .executeUpdate());
    }
    if (withCloudSql) {
      if (!withoutCannedData && !withJpaUnitTest) {
        loadInitialData();
      }
    }
  }

  /**
   * Prepares the fake App Engine environment for use.
   *
   * <p>This should only be called from a <i>non-test context</i>, e.g. {@link
   * google.registry.server.RegistryTestServerMain}, as it doesn't do any of the setup that requires
   * the existence of an {@link ExtensionContext}, which is only available from inside the JUnit
   * runner.
   */
  public void setUp() throws Exception {
    tmpDir = Files.createTempDir();
    setupLogging();
    Set<LocalServiceTestConfig> configs = new HashSet<>();
    if (withUrlFetch) {
      configs.add(new LocalURLFetchServiceTestConfig());
    }

    if (withLocalModules) {
      configs.add(
          new LocalModulesServiceTestConfig()
              .addBasicScalingModuleVersion("default", "1", 1)
              .addBasicScalingModuleVersion("tools", "1", 1)
              .addBasicScalingModuleVersion("backend", "1", 1));
    }
    if (withTaskQueue) {
      File queueFile = new File(tmpDir, "queue.xml");
      asCharSink(queueFile, UTF_8).write(taskQueueXml);
      configs.add(new LocalTaskQueueTestConfig().setQueueXmlPath(queueFile.getAbsolutePath()));
    }
    if (withUserService) {
      configs.add(new LocalUserServiceTestConfig());
    }

    helper = new LocalServiceTestHelper(configs.toArray(new LocalServiceTestConfig[] {}));

    if (withUserService) {
      // Set top-level properties on LocalServiceTestConfig for user login.
      helper
          .setEnvIsLoggedIn(userInfo.isLoggedIn())
          .setEnvAuthDomain(userInfo.authDomain())
          .setEnvEmail(userInfo.email())
          .setEnvIsAdmin(userInfo.isAdmin());
    }

    if (withLocalModules) {
      helper.setEnvInstance("0");
    }
    helper.setUp();
  }

  /** Called after each test method. */
  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    checkArgumentNotNull(context, "The ExtensionContext must not be null");
    if (withCloudSql) {
      if (enableJpaEntityCoverageCheck) {
        jpaIntegrationWithCoverageExtension.afterEach(context);
      } else if (withJpaUnitTest) {
        jpaUnitTestExtension.afterEach(context);
      } else {
        jpaIntegrationTestExtension.afterEach(context);
      }
    }
    tearDown();
  }

  /**
   * Tears down the fake App Engine environment after use.
   *
   * <p>This should only be called from a <i>non-test context</i>, e.g. {@link
   * google.registry.server.RegistryTestServerMain}, as it doesn't do any of the setup that requires
   * the existence of an {@link ExtensionContext}, which is only available from inside the JUnit
   * runner.
   */
  public void tearDown() throws Exception {
    // Resets Objectify. Although it would seem more obvious to do this at the start of a request
    // instead of at the end, this is more consistent with what ObjectifyFilter does in real code.
    helper.tearDown();
    helper = null;
    // Test that Datastore didn't need any indexes we don't have listed in our index file.
    File indexFile = new File(tmpDir, "datastore-indexes-auto.xml");
    try {
      if (!indexFile.exists()) {
        return;
      }
      String indexFileContent = Files.asCharSource(indexFile, UTF_8).read();
      if (indexFileContent.trim().isEmpty()) {
        return;
      }
      Set<String> autoIndexes = getIndexXmlStrings(indexFileContent);
      Set<String> missingIndexes = Sets.difference(autoIndexes, MANUAL_INDEXES);
      if (!missingIndexes.isEmpty()) {
        assertWithMessage("Missing indexes:\n%s", Joiner.on('\n').join(missingIndexes)).fail();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      // Delete the temp directory's contents and then the temp directory itself.
      try (Stream<Path> filesToDelete = walk(tmpDir.toPath())) {
        filesToDelete.sorted(reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
      // Clean up environment setting left behind by AppEngine test instance.
      ApiProxy.setEnvironmentForCurrentThread(null);
    }
  }

  /** Install {@code testing/logging.properties} so logging is less noisy. */
  private static void setupLogging() throws IOException {
    LogManager.getLogManager()
        .readConfiguration(new ByteArrayInputStream(LOGGING_PROPERTIES.getBytes(UTF_8)));
  }

  /** Read a Datastore index file, and parse the indexes into individual strings. */
  private static Set<String> getIndexXmlStrings(String indexFile) {
    ImmutableSet.Builder<String> builder = new ImmutableSet.Builder<>();
    try {
      // To normalize the indexes, we are going to pass them through JSON and then rewrite the xml.
      JSONObject datastoreIndexes = new JSONObject();
      JSONObject indexFileObject = toJSONObject(indexFile);
      Object indexes = indexFileObject.get("datastore-indexes");
      if (indexes instanceof JSONObject) {
        datastoreIndexes = (JSONObject) indexes;
      }
      for (JSONObject index : getJsonAsArray(datastoreIndexes.opt("datastore-index"))) {
        builder.add(getIndexXmlString(index));
      }
    } catch (JSONException e) {
      throw new RuntimeException(
          String.format("Error parsing datastore-indexes-auto.xml: [%s]", indexFile), e);
    }
    return builder.build();
  }

  /**
   * Normalize a value from JSONObject that represents zero, one, or many values. If there were zero
   * values this will be null or an empty JSONArray, depending on how the field was represented in
   * JSON. If there was one value, the object passed in will be that value. If there were more than
   * one values, the object will be a JSONArray containing those values. We will return a list in
   * all cases.
   */
  private static List<JSONObject> getJsonAsArray(@Nullable Object object) throws JSONException {
    ImmutableList.Builder<JSONObject> builder = new ImmutableList.Builder<>();
    if (object instanceof JSONArray) {
      for (int i = 0; i < ((JSONArray) object).length(); ++i) {
        builder.add(((JSONArray) object).getJSONObject(i));
      }
    } else if (object instanceof JSONObject) {
      // When there's only a single entry it won't be wrapped in an array.
      builder.add((JSONObject) object);
    }
    return builder.build();
  }

  /** Turn a JSON representation of an index into xml. */
  private static String getIndexXmlString(JSONObject source) throws JSONException {
    StringBuilder builder = new StringBuilder();
    builder.append(
        String.format(
            "<datastore-index kind=\"%s\" ancestor=\"%s\" source=\"manual\">\n",
            source.getString("kind"), source.get("ancestor").toString()));
    for (JSONObject property : getJsonAsArray(source.get("property"))) {
      builder.append(
          String.format(
              "  <property name=\"%s\" direction=\"%s\"/>\n",
              property.getString("name"), property.getString("direction")));
    }
    return builder.append("</datastore-index>").toString();
  }

  /** Create some fake registrars. */
  public static void loadInitialData() {
    insertSimpleResources(
        ImmutableList.of(
            makeRegistrar1(),
            makeRegistrarContact1(),
            makeRegistrar2(),
            makeRegistrarContact2(),
            makeRegistrarContact3()));
  }

  boolean isWithCloudSql() {
    return withCloudSql;
  }
}
