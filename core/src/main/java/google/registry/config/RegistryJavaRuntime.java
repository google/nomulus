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

import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.api.utils.SystemProperty.Environment.Value;

/**
 * This class provides methods to get/set the Java runtime, e.g. App Engine, local JVM, where the
 * running instance is.
 */
public enum RegistryJavaRuntime {
  /** App Engine runtime. */
  APPENGINE,

  /** Local JVM runtime. Nomulus tool uses this. */
  LOCALJVM,

  /** Unit test runtime. */
  UNITTEST;

  private static RegistryJavaRuntime specifiedRuntime;

  /** Sets the current runtime to the specified value. */
  public static void set(RegistryJavaRuntime runtime) {
    specifiedRuntime = runtime;
  }

  /** Returns the type of Java runtime for the current running instance. */
  public static RegistryJavaRuntime get() {
    if (specifiedRuntime != null) {
      return specifiedRuntime;
    }
    // Use App Engine API(SystemProperty) to determine if the current Java runtime is App Engine.
    if (SystemProperty.environment.equals(Value.Production)) {
      return APPENGINE;
    } else {
      return UNITTEST;
    }
  }
}
