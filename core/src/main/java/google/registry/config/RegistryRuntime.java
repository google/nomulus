// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import com.google.common.base.Ascii;

/** Registry runtime. */
public enum RegistryRuntime {
  /** App Engine runtime. */
  APPENGINE,

  /** Local JVM runtime. Nomulus tool uses this. */
  LOCALJVM,

  /** Unit test runtime. */
  UNITTEST;

  /** Sets this enum as the name of the registry runtime. */
  public RegistryRuntime setup() {
    return setup(SystemPropertySetter.PRODUCTION_IMPL);
  }

  /**
   * Sets this enum as the name of the registry runtime using specified {@link
   * SystemPropertySetter}.
   */
  public RegistryRuntime setup(SystemPropertySetter systemPropertySetter) {
    systemPropertySetter.setProperty(PROPERTY, name());
    return this;
  }

  /** Returns environment configured by system property {@value #PROPERTY}. */
  public static RegistryRuntime get() {
    return valueOf(Ascii.toUpperCase(System.getProperty(PROPERTY, UNITTEST.name())));
  }

  /** System property for configuring which environment we should use. */
  private static final String PROPERTY = "google.registry.runtime";
}
