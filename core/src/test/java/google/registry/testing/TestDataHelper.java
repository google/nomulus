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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newHashMap;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Contains helper methods for dealing with test data. */
public final class TestDataHelper {

  record FileKey(Class<?> context, String filename) {
    static FileKey create(Class<?> context, String filename) {
      return new FileKey(context, filename);
    }
  }

  private static final Map<FileKey, String> fileCache = new ConcurrentHashMap<>();
  private static final Map<FileKey, ByteSource> byteCache = new ConcurrentHashMap<>();

  /**
   * Returns a copy of the given substitution map, updated with the new keys and values.
   *
   * <p>If an existing key is given, its value will be overridden with the new value.
   */
  public static ImmutableMap<String, String> updateSubstitutions(
      @Nullable Map<String, String> baseSubstitutions, String... keysAndValues) {
    checkArgument(
        keysAndValues.length % 2 == 0,
        "keysAndValues must have even number of parameters, but has %s",
        keysAndValues.length);
    Map<String, String> newSubstitutions = newHashMap(nullToEmpty(baseSubstitutions));
    for (int i = 0; i < keysAndValues.length; i += 2) {
      newSubstitutions.put(checkNotNull(keysAndValues[i]), checkNotNull(keysAndValues[i + 1]));
    }
    return ImmutableMap.copyOf(newSubstitutions);
  }

  /**
   * Loads a text file from a directory with a relative path to the location of the specified
   * context class under {@code src/test/resources/}.
   */
  public static String loadFile(Class<?> context, String filename) {
    return fileCache.computeIfAbsent(
        FileKey.create(context, filename), k -> readResourceUtf8(context, filename));
  }

  /**
   * Loads a text file from the "testdata" directory relative to the location of the specified
   * context class, and substitutes in values for placeholders of the form <code>%tagname%</code>.
   */
  public static String loadFile(
      Class<?> context, String filename, @Nullable Map<String, String> substitutions) {
    String fileContents = loadFile(context, filename);
    for (Entry<String, String> entry : nullToEmpty(substitutions).entrySet()) {
      fileContents = fileContents.replaceAll("%" + entry.getKey() + "%", entry.getValue());
    }
    return fileContents;
  }

  /**
   * Loads a {@link ByteSource} from the "testdata" directory relative to the location of the
   * specified context class.
   */
  public static ByteSource loadBytes(Class<?> context, String filename) {
    return byteCache.computeIfAbsent(
        FileKey.create(context, filename), k -> readResourceBytes(context, filename));
  }

  /**
   * Returns the "real" location of the file loaded by the other commands, starting from
   * src/test/resources/.
   */
  public static String filePath(Class<?> context, String filename) {
    String packagePath = context.getPackage().getName().replace('.', '/');
    return String.format("src/test/resources/%s/%s", packagePath, filename);
  }

  /**
   * Constructs the relative classpath for the given {@code filename} under the {@code context}'s
   * package.
   *
   * <p>For example, if the {@code context} is a class with name com.google.registry.FileClasspath,
   * and the given {@code filename} is testdata.txt, then the return value would be
   * com/google/registry/testdata.txt.
   *
   * <p>This function is useful when you just need a relative path starting from the Java root
   * package to the given {@code filename}. The other utility functions in this class either return
   * an absolute path or a relative path but starting from src/ directory.
   */
  public static String fileClassPath(Class<?> context, String filename) {
    String packagePath = context.getPackage().getName().replace('.', '/');
    return String.format("%s/%s", packagePath, filename);
  }

  /** Returns a recursive iterable of all files in the given directory. */
  public static Iterable<Path> listFiles(Class<?> context, String directory) throws Exception {
    URI dir = Resources.getResource(context, directory).toURI();
    ensureFileSystemPresentForUri(dir);
    return MoreFiles.fileTraverser().breadthFirst(Paths.get(dir));
  }

  private static void ensureFileSystemPresentForUri(URI uri) throws IOException {
    if (uri.getScheme().equals(FileSystems.getDefault().provider().getScheme())) {
      // URI maps to default file system (file://...), which must be present. Besides, calling
      // FileSystem.newFileSystem on this URI may trigger FileSystemAlreadyExistsException.
      return;
    }
    try {
      // URI maps to a special file system, e.g., jar:...
      FileSystems.newFileSystem(uri, ImmutableMap.of("create", "true"));
    } catch (FileSystemAlreadyExistsException e) {
      // ignore.
    }
  }
}
