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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import google.registry.config.SystemPropertySetter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.rules.ExternalResource;

/**
 * JUnit Rule for overriding the values Java system properties during tests.
 *
 * <p>In most scenarios this class should be the last rule/extension to apply. In JUnit 5, apply
 * {@code @Order(value = Integer.MAX_VALUE} to the extension.
 */
public final class SystemPropertyRule extends ExternalResource
    implements SystemPropertySetter, BeforeEachCallback, AfterEachCallback {

  /** Class representing a system property key value pair. */
  private static class Property {
    final String key;
    final Optional<String> value;

    Property(String key, Optional<String> value) {
      this.key = checkNotNull(key, "key");
      this.value = checkNotNull(value, "value");
    }

    void set() {
      if (value.isPresent()) {
        System.setProperty(key, value.get());
      } else {
        System.clearProperty(key);
      }
    }
  }

  private boolean isRunning = false;
  private final List<Property> pendings = new ArrayList<>();
  private final Map<String, Property> originals = new HashMap<>();

  /**
   * Change the value of a system property which is restored to its original value after the test.
   *
   * <p>It's safe to call this method multiple times with the same {@code key} within a single test.
   * Only the truly original property value will be restored at the end.
   *
   * <p>This method can be called fluently when declaring the Rule field, or within a Test method.
   *
   * @see java.lang.System#setProperty(String, String)
   */
  @Override
  public SystemPropertyRule setProperty(String key, @Nullable String value) {
    originals.computeIfAbsent(
        key, k -> new Property(k, Optional.ofNullable(System.getProperty(k))));
    Property property = new Property(key, Optional.ofNullable(value));
    if (isRunning) {
      property.set();
    } else {
      pendings.add(property);
    }
    return this;
  }

  @Override
  protected void before() {
    checkState(!isRunning);
    for (Property pending : pendings) {
      pending.set();
    }
    isRunning = true;
  }

  @Override
  protected void after() {
    for (Property original : originals.values()) {
      original.set();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    before();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    after();
  }
}
