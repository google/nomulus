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

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

/**
 * Allows instantiation of Datastore {@code Entity entities} without the heavyweight {@code
 * AppEngineRule}.
 *
 * <p>When used together with {@code JpaIntegrationWithCoverageExtension} or @{@code
 * TestPipelineExtension}, this extension must be registered first. For consistency's sake, it is
 * recommended that the field for this extension be annotated with
 * {@code @org.junit.jupiter.api.Order(value = 1)}. Please refer to {@link
 * google.registry.model.domain.DomainBaseSqlTest} for example, and to <a
 * href="https://junit.org/junit5/docs/current/user-guide/#extensions-registration-programmatic">
 * JUnit 5 User Guide</a> for details of extension ordering.
 */
public class DatastoreEntityExtension implements BeforeEachCallback, AfterEachCallback {

  private static final Environment PLACEHOLDER_ENV = new PlaceholderEnvironment();

  @Override
  public void beforeEach(ExtensionContext context) {
    ApiProxy.setEnvironmentForCurrentThread(PLACEHOLDER_ENV);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // Clear the cached instance.
    ApiProxy.setEnvironmentForCurrentThread(null);
  }

  private static final class PlaceholderEnvironment implements Environment {

    @Override
    public String getAppId() {
      return "PlaceholderAppId";
    }

    @Override
    public Map<String, Object> getAttributes() {
      return ImmutableMap.of();
    }

    @Override
    public String getModuleId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getVersionId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getEmail() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLoggedIn() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAdmin() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAuthDomain() {
      throw new UnsupportedOperationException();
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getRequestNamespace() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getRemainingMillis() {
      throw new UnsupportedOperationException();
    }
  }
}
