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

package google.registry.ui;

import dagger.Module;
import dagger.Provides;

/** Enum defining which JS/CSS files get rendered in a soy templates. */
public enum ConsoleDebug {

  /** Use compiled CSS and JS. */
  PRODUCTION,

  /** Use debug compiled CSS and JS, where symbols are only <i>slightly</i> mangled. */
  DEBUG,

  /**
   * Use debug compiled CSS and raw JS from internal source code dependency-managed directory
   * structure.
   */
  RAW,

  /** Don't use any CSS or JS. This is used by JSTD unit tests. */
  TEST;

  private static final String PROPERTY = "console.debug";
  private static final String DEFAULT = PRODUCTION.name();

  /** Returns value configured by system property {@code #PROPERTY}. */
  public static ConsoleDebug get() {
    return valueOf(System.getProperty(PROPERTY, DEFAULT));
  }

  /** Sets the global {@link ConsoleDebug} state. */
  public static void set(ConsoleDebug value) {
    System.setProperty(PROPERTY, value.toString());
  }

  /** Dagger module for ConsoleDebug. */
  @Module
  public static final class ConsoleConfigModule {

    @Provides
    static ConsoleDebug provideConsoleDebug() {
      return ConsoleDebug.get();
    }
  }
}
