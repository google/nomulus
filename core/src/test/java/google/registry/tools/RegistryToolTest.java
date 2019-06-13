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

import static com.google.common.base.CaseFormat.LOWER_UNDERSCORE;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.reflect.Reflection.getPackageName;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.common.truth.Expect;
import google.registry.testing.SystemPropertyRule;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistryTool}. */
@RunWith(JUnit4.class)
public class RegistryToolTest {

  @Rule
  public final Expect expect = Expect.create();

  @Rule public final SystemPropertyRule systemPropertyRule = new SystemPropertyRule();

  @Before
  public void init() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyRule);
  }

  @Test
  public void test_commandMap_namesAreInAlphabeticalOrder() {
    assertThat(RegistryTool.COMMAND_MAP.keySet()).isInStrictOrder();
  }

  @Test
  public void test_commandMap_includesAllCommands() throws Exception {
    ImmutableSet<?> commandMapClasses = ImmutableSet.copyOf(RegistryTool.COMMAND_MAP.values());
    ImmutableSet<?> classLoaderClasses = getAllCommandClasses();
    // Not using plain old containsExactlyElementsIn() since it produces a huge unreadable blob.
    assertWithMessage("command classes in RegistryTool.COMMAND_MAP but not found by class loader")
        .that(Sets.difference(commandMapClasses, classLoaderClasses))
        .isEmpty();
    assertWithMessage("command classes found by class loader but not in RegistryTool.COMMAND_MAP")
        .that(Sets.difference(classLoaderClasses, commandMapClasses))
        .isEmpty();
  }

  @Test
  public void test_commandMap_namesAreDerivedFromClassNames() {
    for (Map.Entry<String, ? extends Class<? extends Command>> commandEntry :
        RegistryTool.COMMAND_MAP.entrySet()) {
      String className = commandEntry.getValue().getSimpleName();
      expect.that(commandEntry.getKey())
          // JCommander names should match the class name, up to "Command" and case formatting.
          .isEqualTo(UPPER_CAMEL.to(LOWER_UNDERSCORE, className.replaceFirst("Command$", "")));
    }
  }

  @Test
  public void test_commandMap_allCommandsHaveDescriptions() {
    for (Map.Entry<String, ? extends Class<? extends Command>> commandEntry :
        RegistryTool.COMMAND_MAP.entrySet()) {
      Parameters parameters = commandEntry.getValue().getAnnotation(Parameters.class);
      assertThat(parameters).isNotNull();
      assertThat(parameters.commandDescription()).isNotEmpty();
    }
  }

  /**
   * Gets the set of all non-abstract classes implementing the {@link Command} interface (abstract
   * class and interface subtypes of Command aren't expected to have cli commands). Note that this
   * also filters out HelpCommand and ShellCommand, which have special handling in {@link
   * RegistryCli} and aren't in the command map.
   *
   * @throws IOException if reading the classpath resources fails.
   */
  @SuppressWarnings("unchecked")
  private ImmutableSet<Class<? extends Command>> getAllCommandClasses() throws IOException {
    ImmutableSet.Builder<Class<? extends Command>> builder = new ImmutableSet.Builder<>();
    for (ClassInfo classInfo : ClassPath
        .from(getClass().getClassLoader())
        .getTopLevelClassesRecursive(getPackageName(getClass()))) {
      Class<?> clazz = classInfo.load();
      if (Command.class.isAssignableFrom(clazz)
          && !Modifier.isAbstract(clazz.getModifiers())
          && !Modifier.isInterface(clazz.getModifiers())
          && !clazz.equals(HelpCommand.class)
          && !clazz.equals(ShellCommand.class)) {
        builder.add((Class<? extends Command>) clazz);
      }
    }
    return builder.build();
  }
}
