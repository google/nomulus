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

package google.registry.gradle.plugin;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Holds a set of files with a browser-friendly entry point to those files.
 *
 * <p>The file data is lazily generated.
 *
 * <p>If there is at least one file, it's guaranteed that the entry point is one of these files.
 */
@AutoValue
abstract class FilesWithEntryPoint {

  /**
   * All files that are part of this report, keyed from their path to a supplier of their content.
   *
   * <p>The reason we use a supplier instead of loading the content is in case the content is very
   * large...
   *
   * <p>Also, no point in doing IO before we need it!
   */
  abstract ImmutableMap<Path, Supplier<byte[]>> files();

  /**
   * The file that gives access (links...) to all the data in the report.
   *
   * <p>Guaranteed to be a key in {@link #files} if and only if files isn't empty.
   */
  abstract Path entryPoint();

  static FilesWithEntryPoint create(ImmutableMap<Path, Supplier<byte[]>> files, Path entryPoint) {
    checkArgument(files.isEmpty() || files.containsKey(entryPoint));
    return new AutoValue_FilesWithEntryPoint(files, entryPoint);
  }

  static FilesWithEntryPoint createSingleFile(Path path, Supplier<byte[]> data) {
    return create(ImmutableMap.of(path, data), path);
  }
}
