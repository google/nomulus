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

package google.registry.model.smd;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.io.BaseEncoding.base64;

import com.google.common.base.CharMatcher;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlValue;

/**
 * Encoded data representation of a {@link SignedMark} object.
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-smd-02#section-2.4">
 *       draft-lozano-tmch-smd-02 § 2.4</a>
 */
@XmlRootElement(name = "encodedSignedMark")
public class EncodedSignedMark extends ImmutableObject implements AbstractSignedMark {

  private static final String ENCODING_DEFAULT = "base64";

  private static final CharMatcher WHITESPACE = CharMatcher.anyOf(" \t\r\n");

  /** Encoding used for contained data. Default is {@value #ENCODING_DEFAULT}. */
  @XmlAttribute
  String encoding;

  /** Encoded data. */
  @XmlValue String encodedData;

  public String getEncoding() {
    return firstNonNull(encoding, ENCODING_DEFAULT);
  }

  public String getEncodedData() {
    return encodedData == null ? "" : WHITESPACE.removeFrom(encodedData);
  }

  public static EncodedSignedMark create(String encoding, String encodedData) {
    EncodedSignedMark instance = new EncodedSignedMark();
    instance.encoding = encoding;
    instance.encodedData = encodedData;
    return instance;
  }

  public byte[] getBytes() {
    try {
      return base64().decode(getEncodedData());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(e);  // Turn e into a runtime exception.
    }
  }
}
