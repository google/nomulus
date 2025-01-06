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

import com.google.common.io.Files;
import java.nio.file.Path;

/** A utility class for conversion of input file paths into names for entities in the database. */
public final class ListNamingUtils {

  /** Turns a file path into a name suitable for use as the name of a premium or reserved list. */
  public static String convertFilePathToName(Path file) {
    return Files.getNameWithoutExtension(file.getFileName().toString());
  }
}
