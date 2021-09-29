// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import google.registry.model.AppEngineEnvironment;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Allows instantiation of Datastore {@code Entity}s without the heavyweight {@link
 * AppEngineExtension}.
 *
 * <p>When an Ofy key is created, by calling the various Key.create() methods, whether the current
 * executing thread is a GAE thread is checked, which this extension masquerades. This happens
 * frequently when an entity which has the key of another entity as a field is instantiated.
 *
 * <p>When used together with {@code JpaIntegrationWithCoverageExtension} or @{@code
 * TestPipelineExtension}, this extension must be registered first. For consistency's sake, it is
 * recommended that the field for this extension be annotated with
 * {@code @org.junit.jupiter.api.Order(value = 1)}. Please refer to {@link
 * google.registry.model.domain.DomainBaseSqlTest} for example, and to <a
 * href="https://junit.org/junit5/docs/current/user-guide/#extensions-registration-programmatic">
 * JUnit 5 User Guide</a> for details of extension ordering.
 *
 * @see AppEngineEnvironment
 */
public class DatastoreEntityExtension implements BeforeEachCallback, AfterEachCallback {

  private final AppEngineEnvironment environment;

  private boolean allThreads = false;

  public DatastoreEntityExtension(String appId) {
    environment = new AppEngineEnvironment(appId);
  }

  public DatastoreEntityExtension() {
    environment = new AppEngineEnvironment();
  }

  /**
   * Whether all threads should be masqueraded as GAE threads.
   *
   * <p>This is particularly useful when new threads are spawned during a test. For example when
   * testing Beam pipelines, the test pipeline runs the transforms in separate threads than the test
   * thread. If Ofy keys are created in transforms, this value needs to be set to true.
   *
   * <p>Warning: by setting this value to true, any thread spawned by the current JVM will be have
   * the thread local property set to the placeholder value during the execution of the current
   * test, including those running other tests in parallel. This may or may not cause an issue when
   * other tests have {@link AppEngineExtension} registered, that creates a much more fully
   * functional GAE test environment. Consider moving tests using this extension to {@code
   * outcastTest} if necessary.
   */
  public DatastoreEntityExtension allThreads(boolean value) {
    allThreads = value;
    return this;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (allThreads) {
      environment.setEnvironmentForAllThreads();
    } else {
      environment.setEnvironmentForCurrentThread();
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    if (allThreads) {
      environment.unsetEnvironmentForAllThreads();
    } else {
      environment.unsetEnvironmentForCurrentThread();
    }
  }
}
