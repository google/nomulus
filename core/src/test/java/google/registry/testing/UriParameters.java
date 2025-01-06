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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Utility class for working with <a href="http://goo.gl/OQEc8">application/x-www-form-urlencoded
 * content type</a> data.
 */
public final class UriParameters {

  /**
   * Constructs a new parameter map populated with parameters parsed from the specified query string
   * using the specified encoding.
   *
   * @param query the query string, e.g., {@code "q=flowers&n=20"}
   * @return a mutable parameter map representing the query string
   */
  public static ListMultimap<String, String> parse(String query) {
    checkNotNull(query);
    ListMultimap<String, String> map = ArrayListMultimap.create();
    if (!query.isEmpty()) {
      int start = 0;
      while (start <= query.length()) {
        // Find the end of the current parameter.
        int ampersandIndex = query.indexOf('&', start);
        if (ampersandIndex == -1) {
          ampersandIndex = query.length();
        }
        int equalsIndex = query.indexOf('=', start);
        if (equalsIndex > ampersandIndex) {
          // Equal is in the next parameter, so this parameter has no value.
          equalsIndex = -1;
        }
        int paramNameEndIndex = (equalsIndex == -1) ? ampersandIndex : equalsIndex;
        String name = decodeString(query, start, paramNameEndIndex);
        String value = (equalsIndex == -1)
            ? ""
            : decodeString(query, equalsIndex + 1, ampersandIndex);
        map.put(name, value);
        start = ampersandIndex + 1;
      }
    }
    return map;
  }

  private static String decodeString(String str, int start, int end) {
    try {
      return URLDecoder.decode(str.substring(start, end), UTF_8.name());
    } catch (IllegalArgumentException iae) {
      // According to the javadoc of URLDecoder, when the input string is
      // illegal, it could either leave the illegal characters alone or throw
      // an IllegalArgumentException! To deal with both consistently, we
      // ignore IllegalArgumentException and just return the original string.
      return str.substring(start, end);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private UriParameters() {}
}
