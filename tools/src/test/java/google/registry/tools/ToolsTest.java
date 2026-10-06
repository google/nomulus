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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import google.registry.testing.SystemPropertyExtension;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for {@link RegistryTool} and {@link DevTool}. */
class ToolsTest {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void beforeEach() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
  }

  @Test
  void test_commandMap_includesAllCommands() throws Exception {
    ImmutableSet<?> registryToolCommands = ImmutableSet.copyOf(RegistryTool.COMMAND_MAP.values());
    ImmutableSet<?> devToolCommands = ImmutableSet.copyOf(DevTool.COMMAND_MAP.values());
    assertWithMessage("RegistryTool and DevTool have overlapping commands")
        .that(Sets.intersection(registryToolCommands, devToolCommands))
        .isEmpty();
    SetView<?> allCommandsInTools = Sets.union(registryToolCommands, devToolCommands);
    ImmutableSet<?> classLoaderClasses = getAllCommandClasses();
    // Not using plain old containsExactlyElementsIn() since it produces a huge unreadable blob.
    assertWithMessage("command classes in COMMAND_MAP but not found by class loader")
        .that(Sets.difference(allCommandsInTools, classLoaderClasses))
        .isEmpty();
    assertWithMessage("command classes found by class loader but not in COMMAND_MAP")
        .that(Sets.difference(classLoaderClasses, allCommandsInTools))
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void test_commandMap_namesAreInAlphabeticalOrder(
      String toolName, ImmutableMap<String, Class<? extends Command>> commandMap) {
    assertThat(commandMap.keySet()).isInStrictOrder();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void test_commandMap_namesAreDerivedFromClassNames(
      String toolName, ImmutableMap<String, Class<? extends Command>> commandMap) {
    for (Map.Entry<String, ? extends Class<? extends Command>> commandEntry :
        commandMap.entrySet()) {
      String className = commandEntry.getValue().getSimpleName();
      assertThat(commandEntry.getKey())
          // JCommander names should match the class name, up to "Command" and case formatting.
          .isEqualTo(UPPER_CAMEL.to(LOWER_UNDERSCORE, className.replaceFirst("Command$", "")));
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void test_commandMap_allCommandsHaveDescriptions(
      String toolName, ImmutableMap<String, Class<? extends Command>> commandMap) {
    for (Map.Entry<String, ? extends Class<? extends Command>> commandEntry :
        commandMap.entrySet()) {
      Parameters parameters = commandEntry.getValue().getAnnotation(Parameters.class);
      assertThat(parameters).isNotNull();
      assertThat(parameters.commandDescription()).isNotEmpty();
    }
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> provideTestCombinations() {
    return Stream.of(
        Arguments.of("RegistryTool", RegistryTool.COMMAND_MAP),
        Arguments.of("DevTool", DevTool.COMMAND_MAP));
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
    for (ClassInfo classInfo :
        ClassPath.from(getClass().getClassLoader())
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
