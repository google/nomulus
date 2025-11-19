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

package google.registry.util;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Ascii;

/** Registry environments. */
public enum RegistryEnvironment {

  /** Production environment. */
  PRODUCTION,

  /** Development environment. */
  ALPHA,

  /** Load/Backup/Restore Testing environment. */
  CRASH,

  /** Local machine environment. */
  LOCAL,

  /** Quality Assurance environment. */
  QA,

  /** Sandbox environment. */
  SANDBOX,

  /**
   * Unit testing environment.
   *
   * <p>This is the default enum value. This is because it's non-trivial to configure the system
   * property that specifies the environment in our unit tests.
   *
   * <p>Do not use this environment outside of unit tests.
   */
  UNITTEST;

  /** System property for configuring which environment we should use. */
  private static final String PROPERTY = "google.registry.environment";

  /** Name of the environmental variable of the container name. */
  private static final String CONTAINER_ENV = "CONTAINER_NAME";

  private static final boolean IS_CANARY =
      System.getenv().getOrDefault(CONTAINER_ENV, "").endsWith("-canary");

  /**
   * A thread local boolean that can be set in tests to indicate some code is running in a local
   * test server.
   *
   * <p>Certain API calls (like calls to Cloud Tasks) are hard to stub when they run in the test
   * server because the test server does not allow arbitrary injection of dependencies. Instead,
   * code running in the server can check this value and decide whether to skip these API calls.
   *
   * <p>The value is set to false by default and can only be set to true in unit test environment.
   * It is set to {@code true} in {@code start()} and {@code false} in {@code stop()} in {@code
   * TestServer}.
   */
  private static final ThreadLocal<Boolean> IN_TEST_SERVER = ThreadLocal.withInitial(() -> false);

  /** Sets this enum as the name of the registry environment. */
  public RegistryEnvironment setup() {
    return setup(SystemPropertySetter.PRODUCTION_IMPL);
  }

  /**
   * Sets this enum as the name of the registry environment using specified {@link
   * SystemPropertySetter}.
   */
  public RegistryEnvironment setup(SystemPropertySetter systemPropertySetter) {
    systemPropertySetter.setProperty(PROPERTY, name());
    return this;
  }

  /** Returns environment configured by system property {@value #PROPERTY}. */
  public static RegistryEnvironment get() {
    return valueOf(Ascii.toUpperCase(System.getProperty(PROPERTY, UNITTEST.name())));
  }

  public static boolean isCanary() {
    return IS_CANARY;
  }

  public static void setIsInTestDriver(boolean value) {
    checkState(RegistryEnvironment.get() == RegistryEnvironment.UNITTEST);
    IN_TEST_SERVER.set(value);
  }

  public static boolean isInTestServer() {
    return IN_TEST_SERVER.get();
  }
}
