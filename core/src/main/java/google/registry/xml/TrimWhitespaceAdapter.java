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

package google.registry.xml;

import com.google.common.base.CharMatcher;
import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import javax.annotation.Nullable;

/**
 * {@link XmlAdapter} which trims all whitespace surrounding a String.
 *
 * <p>This is primarily useful for <code>@XmlValue</code>-annotated fields in JAXB objects, as XML
 * values can commonly be formatted like so:
 *
 * <pre>{@code
 * <ns:tag>
 *   XML value here.
 * </ns:tag>
 * }</pre>
 */
public class TrimWhitespaceAdapter extends XmlAdapter<String, String> {

  private static final CharMatcher WHITESPACE = CharMatcher.anyOf(" \t\r\n");

  @Override
  @Nullable
  public String unmarshal(@Nullable String value) {
    return (value == null) ? null : WHITESPACE.trimFrom(value);
  }

  @Override
  @Nullable
  public String marshal(@Nullable String str) {
    return str;
  }
}
