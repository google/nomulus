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

import static com.google.common.io.Resources.asByteSource;
import static com.google.common.io.Resources.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;

/** Utility methods related to reading java resources. */
public final class ResourceUtils {

  private ResourceUtils() {}

  /** Loads a resource from a file as a string, assuming UTF-8 encoding. */
  public static String readResourceUtf8(String filename) {
    return readResourceUtf8(getResource(filename));
  }

  /**
   * Loads a resource from a file (specified relative to the contextClass) as a string, assuming
   * UTF-8 encoding.
   */
  public static String readResourceUtf8(Class<?> contextClass, String filename) {
    return readResourceUtf8(getResource(contextClass, filename));
  }

  /** Loads a resource from a URL as a string, assuming UTF-8 encoding. */
  public static String readResourceUtf8(URL url) {
    try {
      return Resources.toString(url, UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to load resource: " + url, e);
    }
  }

  /** Loads a file (specified relative to the contextClass) as a ByteSource. */
  public static ByteSource readResourceBytes(Class<?> contextClass, String filename) {
    return asByteSource(getResource(contextClass, filename));
  }
}
