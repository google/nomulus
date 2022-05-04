// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.java8compatibility;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that a list of user-specified jars are compatible with Java 8.
 *
 * <p>User should use the {@link #JAR_LIST_PROPERTY_NAME} system property to pass in the
 * fully-qualified filenames of the jars to be verified, separate by comma.
 *
 * <p>To verify Java 8 compatibility, it is not sufficient to run tests with the Java 8 JVM. Many
 * API jars, notably those for GCP services, are not exercised by tests. This class takes the
 * conservative approach, and fails if any class is not compatible, regardless if it is in a
 * production code path.
 */
class ByteCodeVersionTest {

  public static final String JAR_LIST_PROPERTY_NAME = "all_prod_jars";

  /**
   * The major version of bytecode at Java 8 target level. Technically this is a short value.
   * However, a byte is sufficient for the lifetime of this class.
   */
  private static final byte JAVA8_MAJOR_VERSION = (byte) 52;
  // Offset of the major version number's lower byte in the class file.
  private static final int MAJOR_VERSION_OFFSET = 7;
  private static final int HEADER_LENGTH = MAJOR_VERSION_OFFSET + 1;

  private static ImmutableList<String> jarsPaths;

  @BeforeAll
  static void setup() {
    jarsPaths =
        ImmutableList.copyOf(
            Splitter.on(',')
                .omitEmptyStrings()
                .trimResults()
                .split(System.getProperty(JAR_LIST_PROPERTY_NAME)));
    assertWithMessage("Jar list is empty.").that(jarsPaths).isNotEmpty();
    jarsPaths.forEach(path -> assertThat(path).endsWith(".jar"));
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> provideJarNames() {
    return jarsPaths.stream()
        .filter(pathStr -> new File(pathStr).exists())
        .map(pathStr -> Arguments.of(pathStr.substring(pathStr.lastIndexOf('/') + 1), pathStr));
  }

  @ParameterizedTest(name = "verifyBytecode_isJava8: {0}")
  @MethodSource("provideJarNames")
  void verifyBytecode_isJava8(String jarName, String jarPath) throws IOException {
    ZipFile jarFile = new ZipFile(jarPath);
    for (Enumeration<? extends ZipEntry> entries = jarFile.entries(); entries.hasMoreElements(); ) {
      verifyOneZipEntry(jarPath, jarFile, entries.nextElement());
    }
  }

  private void verifyOneZipEntry(String jarPath, ZipFile jarFile, ZipEntry entry)
      throws IOException {
    /**
     * A few jars (jaxb-api and bcprov-jdk15on) include Java 9+ module classes under
     * META-INF/versions. They don't affect Java 8.
     */
    if (entry.getName().startsWith("META-INF/versions/")) {
      return;
    }
    if (!entry.getName().endsWith(".class")) {
      return;
    }
    // Several jars include module-info.class at the root instead of under META-INF. This does not
    // affect Java 8 either.
    if (entry.getName().endsWith("module-info.class")) {
      return;
    }
    try (InputStream inputStream = jarFile.getInputStream(entry)) {
      byte[] header = inputStream.readNBytes(HEADER_LENGTH);
      assertWithMessage("Malformed java class %s in %s.", entry.getName(), jarPath)
          .that(header != null && header.length == HEADER_LENGTH)
          .isTrue();
      assertWithMessage("Incompatible with Java 8: Class %s in %s.", entry.getName(), jarPath)
          .that(header[MAJOR_VERSION_OFFSET])
          .isAtMost(JAVA8_MAJOR_VERSION);
    }
  }
}
