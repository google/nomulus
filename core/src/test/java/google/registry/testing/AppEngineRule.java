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
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.DualDatabaseTestInvocationContextProvider.injectTmForDualDatabaseTest;
import static google.registry.testing.DualDatabaseTestInvocationContextProvider.restoreTmAfterDualDatabaseTest;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.json.XML.toJSONObject;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalModulesServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig;
import com.google.appengine.tools.development.testing.LocalURLFetchServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalUserServiceTestConfig;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import google.registry.util.Clock;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.LogManager;
import javax.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit Rule for managing the App Engine testing environment.
 *
 * <p>Generally you'll want to configure the environment using only the services you need (because
 * each service is expensive to create).
 *
 * <p>This rule also resets global Objectify for the current thread.
 *
 * <p>This class works with both JUnit 4 and JUnit 5. With JUnit 4, the test runner calls {@link
 * #apply(Statement, Description)}, which in turns calls {@link #before()} on entry and {@link
 * #after()} on exit. With JUnit 5, the test runner calls {@link #beforeEach(ExtensionContext)} and
 * {@link #afterEach(ExtensionContext)}.
 *
 * @see org.junit.rules.ExternalResource
 */
public final class AppEngineRule extends ExternalResource
    implements BeforeEachCallback, AfterEachCallback {

  public static final String NEW_REGISTRAR_GAE_USER_ID = "666";
  public static final String THE_REGISTRAR_GAE_USER_ID = "31337";
  public static final String MARLA_SINGER_GAE_USER_ID = "12345";

  /**
   * The GAE testing library requires queue.xml to be a file, not a resource in a jar, so we read it
   * in here and write it to a temporary file later.
   */
  private static final String QUEUE_XML =
      readResourceUtf8("google/registry/env/common/default/WEB-INF/queue.xml");

  /** A parsed version of the indexes used in the prod code. */
  private static final Set<String> MANUAL_INDEXES = getIndexXmlStrings(
      readResourceUtf8(
          "google/registry/env/common/default/WEB-INF/datastore-indexes.xml"));

  private static final String LOGGING_PROPERTIES =
      readResourceUtf8(AppEngineRule.class, "logging.properties");

  private LocalServiceTestHelper helper;

  /** A rule-within-a-rule to provide a temporary folder for AppEngineRule's internal temp files. */
  TemporaryFolder temporaryFolder = new TemporaryFolder();
  /**
   * Sets up a SQL database when running on JUnit 5. This is for test classes that are not member of
   * the {@code SqlIntegrationTestSuite}.
   */
  JpaIntegrationTestRule jpaIntegrationTestRule = null;
  /**
   * Sets up a SQL database when running on JUnit 5 and records the JPA entities tested by each test
   * class. This is for {@code SqlIntegrationTestSuite} members.
   */
  JpaIntegrationWithCoverageExtension jpaIntegrationWithCoverageExtension = null;

  JpaUnitTestRule jpaUnitTestRule;

  private boolean withDatastoreAndCloudSql;
  private boolean enableJpaEntityCoverageCheck;
  private boolean withJpaUnitTest;
  private boolean withLocalModules;
  private boolean withTaskQueue;
  private boolean withUserService;
  private boolean withUrlFetch;
  private Clock clock;

  private String taskQueueXml;
  private UserInfo userInfo;

  // Test Objectify entity classes to be used with this AppEngineRule instance.
  private ImmutableList<Class<?>> ofyTestEntities;
  private ImmutableList<Class<?>> jpaTestEntities;

  /** Builder for {@link AppEngineRule}. */
  public static class Builder {

    private AppEngineRule rule = new AppEngineRule();
    private ImmutableList.Builder<Class<?>> ofyTestEntities = new ImmutableList.Builder<>();
    private ImmutableList.Builder<Class<?>> jpaTestEntities = new ImmutableList.Builder<>();

    /** Turn on the Datastore service and the Cloud SQL service. */
    public Builder withDatastoreAndCloudSql() {
      rule.withDatastoreAndCloudSql = true;
      return this;
    }
    /**
     * Enables JPA entity coverage check if {@code enabled} is true. This should only be enabled for
     * members of SqlIntegrationTestSuite.
     */
    public Builder enableJpaEntityCoverageCheck(boolean enabled) {
      rule.enableJpaEntityCoverageCheck = enabled;
      return this;
    }

    /** Turn on the use of local modules. */
    public Builder withLocalModules() {
      rule.withLocalModules = true;
      return this;
    }

    /** Turn on the task queue service. */
    public Builder withTaskQueue() {
      return withTaskQueue(QUEUE_XML);
    }

    /** Turn on the task queue service with a specified set of queues. */
    public Builder withTaskQueue(String taskQueueXml) {
      rule.withTaskQueue = true;
      rule.taskQueueXml = taskQueueXml;
      return this;
    }

    /** Turn on the URL Fetch service. */
    public Builder withUrlFetch() {
      rule.withUrlFetch = true;
      return this;
    }

    public Builder withClock(Clock clock) {
      rule.clock = clock;
      return this;
    }

    public Builder withUserService(UserInfo userInfo) {
      rule.withUserService = true;
      rule.userInfo = userInfo;
      return this;
    }

    /**
     * Declares test-only entities to be registered with {@code ObjectifyService}.
     *
     * <p>Note that {@code ObjectifyService} silently replaces the current registration for a given
     * kind when a different class is registered for this kind. Since {@code ObjectifyService} does
     * not support de-registration, each test entity class must be of a unique kind across the
     * entire code base. Although this requirement can be worked around by using different {@code
     * ObjectifyService} instances for each test (class), the setup overhead would rise
     * significantly.
     *
     * @see AppEngineRule#register(Class)
     */
    @SafeVarargs
    public final Builder withOfyTestEntities(Class<?>... entities) {
      ofyTestEntities.add(entities);
      rule.withJpaUnitTest = true;
      return this;
    }

    public Builder withJpaUnitTestEntities(Class<?>... entities) {
      jpaTestEntities.add(entities);
      return this;
    }

    public AppEngineRule build() {
      checkState(
          !rule.enableJpaEntityCoverageCheck || rule.withDatastoreAndCloudSql,
          "withJpaEntityCoverageCheck enabled without Cloud SQL");
      checkState(
          !rule.withJpaUnitTest || rule.withDatastoreAndCloudSql,
          "withOfyTestEntities enabled without Cloud SQL");
      checkState(
          !rule.withJpaUnitTest || !rule.enableJpaEntityCoverageCheck,
          "withOfyTestEntities cannot be set when enableJpaEntityCoverageCheck");
      rule.ofyTestEntities = this.ofyTestEntities.build();
      rule.jpaTestEntities = this.jpaTestEntities.build();
      return rule;
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
        .setInternationalizedAddress(new RegistrarAddress.Builder()
            .setStreet(ImmutableList.of("123 Example Boulevard"))
            .setCity("Williamsburg")
            .setState("NY")
            .setZip("11211")
            .setCountryCode("US")
            .build())
        .setLocalizedAddress(new RegistrarAddress.Builder()
            .setStreet(ImmutableList.of("123 Example B\u0151ulevard"))
            .setCity("Williamsburg")
            .setState("NY")
            .setZip("11211")
            .setCountryCode("US")
            .build())
        .setPhoneNumber("+1.3334445555")
        .setPhonePasscode("12345")
        .setContactsRequireSyncing(true);
  }

  /** Public factory for first Registrar to allow comparison against stored value in unit tests. */
  public static Registrar makeRegistrar1() {
    return makeRegistrarCommon()
        .setClientId("NewRegistrar")
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
        .setClientId("TheRegistrar")
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
   * Public factory for first RegistrarContact to allow comparison
   * against stored value in unit tests.
   */
  public static RegistrarContact makeRegistrarContact1() {
    return new RegistrarContact.Builder()
        .setParent(makeRegistrar1())
        .setName("Jane Doe")
        .setVisibleInWhoisAsAdmin(true)
        .setVisibleInWhoisAsTech(false)
        .setEmailAddress("janedoe@theregistrar.com")
        .setPhoneNumber("+1.1234567890")
        .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
        .build();
  }

  /**
   * Public factory for second RegistrarContact to allow comparison
   * against stored value in unit tests.
   */
  public static RegistrarContact makeRegistrarContact2() {
    return new RegistrarContact.Builder()
        .setParent(makeRegistrar2())
        .setName("John Doe")
        .setEmailAddress("johndoe@theregistrar.com")
        .setPhoneNumber("+1.1234567890")
        .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
        .setGaeUserId(THE_REGISTRAR_GAE_USER_ID)
        .build();
  }

  public static RegistrarContact makeRegistrarContact3() {
    return new RegistrarContact.Builder()
        .setParent(makeRegistrar2())
        .setName("Marla Singer")
        .setEmailAddress("Marla.Singer@crr.com")
        .setRegistryLockEmailAddress("Marla.Singer.RegistryLock@crr.com")
        .setPhoneNumber("+1.2128675309")
        .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
        .setGaeUserId(MARLA_SINGER_GAE_USER_ID)
        .setAllowedToSetRegistryLockPassword(true)
        .setRegistryLockPassword("hi")
        .build();
  }

  /** Called before every test method. JUnit 5 only. */
  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    before();
    if (withDatastoreAndCloudSql) {
      JpaTestRules.Builder builder = new JpaTestRules.Builder();
      if (clock != null) {
        builder.withClock(clock);
      }
      if (enableJpaEntityCoverageCheck) {
        jpaIntegrationWithCoverageExtension = builder.buildIntegrationWithCoverageExtension();
        jpaIntegrationWithCoverageExtension.beforeEach(context);
      } else if (withJpaUnitTest) {
        jpaUnitTestRule =
            builder
                .withEntityClass(jpaTestEntities.toArray(new Class[jpaTestEntities.size()]))
                .buildUnitTestRule();
        jpaUnitTestRule.before();
      } else {
        jpaIntegrationTestRule = builder.buildIntegrationTestRule();
        jpaIntegrationTestRule.before();
      }
    }
    injectTmForDualDatabaseTest(context);
  }

  /** Called after each test method. JUnit 5 only. */
  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    if (withDatastoreAndCloudSql) {
      if (enableJpaEntityCoverageCheck) {
        jpaIntegrationWithCoverageExtension.afterEach(context);
      } else if (withJpaUnitTest) {
        jpaUnitTestRule.after();
      } else {
        jpaIntegrationTestRule.after();
      }
    }
    after();
    restoreTmAfterDualDatabaseTest(context);
  }

  /**
   * Hack to make sure AppEngineRule is always wrapped in a {@link JpaIntegrationWithCoverageRule}.
   * JUnit 4 only.
   */
  // Note: Even with @EnableRuleMigrationSupport, JUnit5 runner does not call this method.
  // Note 2: Do not migrate members of SqlIntegrationTestSuite to JUnit5 individually.
  // TODO(weiminyu): migrate SqlIntegrationTestSuite in one go.
  @Override
  public Statement apply(Statement base, Description description) {
    Statement statement = base;
    if (withDatastoreAndCloudSql) {
      JpaTestRules.Builder builder = new JpaTestRules.Builder();
      if (clock != null) {
        builder.withClock(clock);
      }
      checkState(
          !enableJpaEntityCoverageCheck,
          "JUnit4 tests must not enable withJpaEntityCoverageCheck.");
      statement = builder.buildIntegrationTestRule().apply(base, description);
    }
    return super.apply(statement, description);
  }

  @Override
  protected void before() throws IOException {
    setupLogging();
    temporaryFolder.create();
    Set<LocalServiceTestConfig> configs = new HashSet<>();
    if (withUrlFetch) {
      configs.add(new LocalURLFetchServiceTestConfig());
    }
    if (withDatastoreAndCloudSql) {
      configs.add(new LocalDatastoreServiceTestConfig()
          // We need to set this to allow cross entity group transactions.
          .setApplyAllHighRepJobPolicy()
          // This causes unit tests to write a file containing any indexes the test required. We
          // can use that file below to make sure we have the right indexes in our prod code.
          .setNoIndexAutoGen(false));
      // This forces app engine to write the generated indexes to a usable location.
      System.setProperty("appengine.generated.dir", temporaryFolder.getRoot().getAbsolutePath());
    }
    if (withLocalModules) {
      configs.add(new LocalModulesServiceTestConfig()
          .addBasicScalingModuleVersion("default", "1", 1)
          .addBasicScalingModuleVersion("tools", "1", 1)
          .addBasicScalingModuleVersion("backend", "1", 1));
    }
    if (withTaskQueue) {
      File queueFile = temporaryFolder.newFile("queue.xml");
      Files.asCharSink(queueFile, UTF_8).write(taskQueueXml);
      configs.add(new LocalTaskQueueTestConfig()
          .setQueueXmlPath(queueFile.getAbsolutePath()));
    }
    if (withUserService) {
      configs.add(new LocalUserServiceTestConfig());
    }

    helper = new LocalServiceTestHelper(configs.toArray(new LocalServiceTestConfig[]{}));

    if (withUserService) {
      // Set top-level properties on LocalServiceTestConfig for user login.
      helper
          .setEnvIsLoggedIn(userInfo.isLoggedIn())
          // This envAttributes thing is the only way to set userId.
          // see https://code.google.com/p/googleappengine/issues/detail?id=3579
          .setEnvAttributes(
              ImmutableMap.of(
                  "com.google.appengine.api.users.UserService.user_id_key", userInfo.gaeUserId()))
          .setEnvAuthDomain(userInfo.authDomain())
          .setEnvEmail(userInfo.email())
          .setEnvIsAdmin(userInfo.isAdmin());
    }

    if (clock != null) {
      helper.setClock(() -> clock.nowUtc().getMillis());
    }

    if (withLocalModules) {
      helper.setEnvInstance("0");
    }

    helper.setUp();

    if (withDatastoreAndCloudSql) {
      ObjectifyService.initOfy();
      // Reset id allocation in ObjectifyService so that ids are deterministic in tests.
      ObjectifyService.resetNextTestId();
      loadInitialData();
      this.ofyTestEntities.forEach(AppEngineRule::register);
    }
  }

  @Override
  protected void after() {
    // Resets Objectify. Although it would seem more obvious to do this at the start of a request
    // instead of at the end, this is more consistent with what ObjectifyFilter does in real code.
    ObjectifyFilter.complete();
    helper.tearDown();
    helper = null;
    // Test that Datastore didn't need any indexes we don't have listed in our index file.
    File indexFile = new File(temporaryFolder.getRoot(), "datastore-indexes-auto.xml");
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
      temporaryFolder.delete();
    }
  }

  /**
   * Registers test-only Objectify entities and checks for re-registrations for the same kind by
   * different classes.
   */
  private static void register(Class<?> entityClass) {
    String kind = Key.getKind(entityClass);
    Optional.ofNullable(com.googlecode.objectify.ObjectifyService.factory().getMetadata(kind))
        .ifPresent(
            meta ->
                checkState(
                    meta.getEntityClass() == entityClass,
                    "Cannot register %s. The Kind %s is already registered with %s.",
                    entityClass.getName(),
                    kind,
                    meta.getEntityClass().getName()));
    com.googlecode.objectify.ObjectifyService.register(entityClass);
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
    } else if (object instanceof JSONObject){
      // When there's only a single entry it won't be wrapped in an array.
      builder.add((JSONObject) object);
    }
    return builder.build();
  }

  /** Turn a JSON representation of an index into xml. */
  private static String getIndexXmlString(JSONObject source) throws JSONException {
    StringBuilder builder = new StringBuilder();
    builder.append(String.format(
        "<datastore-index kind=\"%s\" ancestor=\"%s\" source=\"manual\">\n",
        source.getString("kind"),
        source.get("ancestor").toString()));
    for (JSONObject property : getJsonAsArray(source.get("property"))) {
      builder.append(String.format(
          "  <property name=\"%s\" direction=\"%s\"/>\n",
          property.getString("name"),
          property.getString("direction")));
    }
    return builder.append("</datastore-index>").toString();
  }

  /** Create some fake registrars. */
  public static void loadInitialData() {
    persistSimpleResources(
        ImmutableList.of(
            makeRegistrar1(),
            makeRegistrarContact1(),
            makeRegistrar2(),
            makeRegistrarContact2(),
            makeRegistrarContact3()));
  }

  boolean isWithDatastoreAndCloudSql() {
    return withDatastoreAndCloudSql;
  }
}
