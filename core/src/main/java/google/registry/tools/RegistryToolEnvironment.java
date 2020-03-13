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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryEnvironment;
import google.registry.config.SystemPropertySetter;

/** Enum of production environments, used for the {@code --environment} flag. */
public enum RegistryToolEnvironment {
  PRODUCTION(RegistryEnvironment.PRODUCTION),
  ALPHA(RegistryEnvironment.ALPHA),
  CRASH(RegistryEnvironment.CRASH),
  QA(RegistryEnvironment.QA),
  SANDBOX(RegistryEnvironment.SANDBOX),
  LOCALHOST(RegistryEnvironment.LOCAL),
  UNITTEST(RegistryEnvironment.UNITTEST),
  PDT(RegistryEnvironment.PRODUCTION, ImmutableMap.of(
      "google.registry.rde.key.receiver",
          "pdt-escrow-test@icann.org"));

  private static final ImmutableList<String> FLAGS = ImmutableList.of("-e", "--environment");
  private static RegistryToolEnvironment instance;
  private final RegistryEnvironment actualEnvironment;
  private final ImmutableMap<String, String> extraProperties;

  RegistryToolEnvironment(
      RegistryEnvironment actualEnvironment,
      ImmutableMap<String, String> extraProperties) {
    this.actualEnvironment = actualEnvironment;
    this.extraProperties = extraProperties;
  }

  RegistryToolEnvironment(RegistryEnvironment actualEnvironment) {
    this(actualEnvironment, ImmutableMap.of());
  }

  /**
   * Extracts environment from command-line arguments.
   *
   * <p>This operation can't be performed by JCommander because it needs to happen before any
   * command classes get loaded. This is because this routine will set system properties that are
   * referenced by static initializers for many classes. This approach also allows us to change the
   * default values of JCommander parameters, based on the selected environment.
   *
   * @see #get()
   */
  static RegistryToolEnvironment parseFromArgs(String[] args) {
    return valueOf(Ascii.toUpperCase(getFlagValue(args, FLAGS)));
  }

  /**
   * Returns the current environment.
   *
   * <p>This should be called after {@link #parseFromArgs(String[])}.
   */
  public static RegistryToolEnvironment get() {
    checkState(instance != null, "No RegistryToolEnvironment has been set up");
    return instance;
  }

  /** Resets static class state to uninitialized state. */
  @VisibleForTesting
  static void reset() {
    instance = null;
  }

  /** Sets up execution environment. Call this method before any classes are loaded. */
  void setup() {
    setup(SystemPropertySetter.PRODUCTION_IMPL);
  }

  /** Sets up execution environment. Call this method before any classes are loaded. */
  @VisibleForTesting
  void setup(SystemPropertySetter systemPropertySetter) {
    instance = this;
    actualEnvironment.setup(systemPropertySetter);
    for (ImmutableMap.Entry<String, String> entry : extraProperties.entrySet()) {
      systemPropertySetter.setProperty(entry.getKey(), entry.getValue());
    }
  }

  /** Extracts value from command-line arguments associated with any {@code flags}. */
  private static String getFlagValue(String[] args, Iterable<String> flags) {
    for (String flag : flags) {
      boolean expecting = false;
      for (int i = 0; i < args.length; i++) {
        // XXX: Kludge to stop processing once we reach what is *probably* the command name.
        //      This is necessary in order to allow commands to accept arguments named '-e'.
        //      This code should probably be updated, should any zero arity flags be added to
        //      RegistryCli, LoggingParameters, or AppEngineConnection.
        if (args[i].startsWith("-")) {
          expecting = !args[i].contains("=");
        } else {
          if (expecting) {
            expecting = false;
          } else {
            break;  // This is the command name, unless zero arity flags were added.
          }
        }
        if (args[i].equals(flag)) {
          checkArgument(i + 1 < args.length, "%s flag missing value.", flag);
          return args[i + 1];
        }
        if (args[i].startsWith(flag + "=")
            || args[i].startsWith(flag + " ")) {
          return args[i].substring(flag.length() + 1);
        }
      }
    }
    throw new IllegalArgumentException("Please specify the environment flag, e.g. -e production.");
  }
}
