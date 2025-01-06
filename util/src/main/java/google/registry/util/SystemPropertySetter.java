// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import javax.annotation.Nullable;

/**
 * Wrapper interface around {@link System#setProperty(String, String)} and {@link
 * System#clearProperty(String)}. Tests that modify system properties may provide custom
 * implementations that keeps track of changes and restores original property values on test
 * completion.
 */
public interface SystemPropertySetter {

  /**
   * Updates the system property specified by {@code key}. If {@code value} is not null, {@link
   * System#setProperty(String, String)} is invoked; otherwise {@link System#clearProperty(String)}
   * is invoked.
   */
  SystemPropertySetter setProperty(String key, @Nullable String value);

  /** Production implementation of {@link SystemPropertySetter}. */
  SystemPropertySetter PRODUCTION_IMPL =
      new SystemPropertySetter() {
        @Override
        public SystemPropertySetter setProperty(String key, @Nullable String value) {
          if (value == null) {
            System.clearProperty(key);
          } else {
            System.setProperty(key, value);
          }
          return this;
        }
      };
}
