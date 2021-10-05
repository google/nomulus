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
import static google.registry.testing.DualDatabaseTestInvocationContextProvider.injectTmForDualDatabaseTest;
import static google.registry.testing.DualDatabaseTestInvocationContextProvider.restoreTmAfterDualDatabaseTest;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.walk;
import static java.util.Comparator.reverseOrder;
import static org.json.XML.toJSONObject;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
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
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.IdService;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
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

  private boolean withDatastore;
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
  private ImmutableList<Class<?>> ofyTestEntities;
  private ImmutableList<Class<?>> jpaTestEntities;

  /** Builder for {@link AppEngineExtension}. */
  public static class Builder {

    private AppEngineExtension extension = new AppEngineExtension();
    private ImmutableList.Builder<Class<?>> ofyTestEntities = new ImmutableList.Builder<>();
    private ImmutableList.Builder<Class<?>> jpaTestEntities = new ImmutableList.Builder<>();

    /** Turn on the Datastore service and the Cloud SQL service. */
    public Builder withDatastoreAndCloudSql() {
      extension.withDatastore = true;
      extension.withCloudSql = true;
      return this;
    }

    /** Turns on Cloud SQL only, for use by test data generators. */
    public Builder withCloudSql() {
      extension.withCloudSql = true;
      extension.withDatastore = false;
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
     * @see AppEngineExtension#register(Class)
     */
    @SafeVarargs
    public final Builder withOfyTestEntities(Class<?>... entities) {
      ofyTestEntities.add(entities);
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
      extension.ofyTestEntities = this.ofyTestEntities.build();
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
   * Public factory for second RegistrarContact to allow comparison against stored value in unit
   * tests.
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

  /** Called before every test method. */
  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    checkArgumentNotNull(context, "The ExtensionContext must not be null");
    setUp();
    if (withCloudSql) {
      JpaTestExtensions.Builder builder =
          new JpaTestExtensions.Builder().withEntityClass(jpaTestEntities.toArray(new Class[0]));
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
    }
    if (isWithDatastoreAndCloudSql()) {
      injectTmForDualDatabaseTest(context);
    }
    if (withDatastore || withCloudSql) {
      if (!withoutCannedData && (tm().isOfy() || (withCloudSql && !withJpaUnitTest))) {
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
    if (withDatastore) {
      configs.add(
          new LocalDatastoreServiceTestConfig()
              // We need to set this to allow cross entity group transactions.
              .setApplyAllHighRepJobPolicy()
              // This causes unit tests to write a file containing any indexes the test required. We
              // can use that file below to make sure we have the right indexes in our prod code.
              .setNoIndexAutoGen(false));
      // This forces App Engine to write the generated indexes to a usable location.
      System.setProperty("appengine.generated.dir", tmpDir.getAbsolutePath());
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
          // This envAttributes thing is the only way to set userId.
          // see https://code.google.com/p/googleappengine/issues/detail?id=3579
          .setEnvAttributes(
              ImmutableMap.of(
                  "com.google.appengine.api.users.UserService.user_id_key", userInfo.gaeUserId()))
          .setEnvAuthDomain(userInfo.authDomain())
          .setEnvEmail(userInfo.email())
          .setEnvIsAdmin(userInfo.isAdmin());
    }

    if (withLocalModules) {
      helper.setEnvInstance("0");
    }

    helper.setUp();

    if (withDatastore) {
      ObjectifyService.initOfy();
      // Reset id allocation in ObjectifyService so that ids are deterministic in tests.
      IdService.resetNextTestId();
      this.ofyTestEntities.forEach(AppEngineExtension::register);
    }
  }

  /** Called after each test method. */
  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    checkArgumentNotNull(context, "The ExtensionContext must not be null");
    try {
      // If there is a replay extension, we'll want to call its replay() method.
      //
      // We have to provide this hook here for ReplayExtension instead of relying on
      // ReplayExtension's afterEach() method because of ordering and the conflation of environment
      // initialization and basic entity initialization.
      //
      // ReplayExtension's beforeEach() has to be called before this so that the entities that we
      // initialize (e.g. "TheRegistrar") also get replayed.  But that means that ReplayExtension's
      // afterEach() won't be called until after ours.  Since we tear down the datastore and SQL
      // database in our own afterEach(), ReplayExtension's afterEach() would fail if we let the
      // replay happen there.
      ReplayExtension replayer =
          (ReplayExtension)
              context.getStore(ExtensionContext.Namespace.GLOBAL).get(ReplayExtension.class);
      if (replayer != null) {
        replayer.replay();
      }

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
    } finally {
      if (isWithDatastoreAndCloudSql()) {
        restoreTmAfterDualDatabaseTest(context);
      }
    }
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
    System.err.println("resetting objectify");
    ObjectifyFilter.complete();
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

  boolean isWithDatastoreAndCloudSql() {
    return withDatastore && withCloudSql;
  }
}
