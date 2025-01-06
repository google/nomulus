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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Optional;

/**
 * Enumerates the DNSSEC digest types for use with Delegation Signer records.
 *
 * <p>This also enforces the set of types that are valid for use with Cloud DNS. Customers cannot
 * create DS records containing any other digest type.
 *
 * <p>The complete list can be found here:
 * https://www.iana.org/assignments/ds-rr-types/ds-rr-types.xhtml
 */
public enum DigestType {
  SHA1(1, 20),
  SHA256(2, 32),
  // Algorithm number 3 is GOST R 34.11-94 and is deliberately NOT SUPPORTED.
  // This algorithm was reviewed by ise-crypto and deemed academically broken (b/207029800).
  // In addition, RFC 8624 specifies that this algorithm MUST NOT be used for DNSSEC delegations.
  // TODO(sarhabot@): Add note in Cloud DNS code to notify the Registry of any new changes to
  // supported digest types.
  SHA384(4, 48);

  private final int wireValue;
  private final int bytes;

  DigestType(int wireValue, int bytes) {
    this.wireValue = wireValue;
    this.bytes = bytes;
  }

  private static final ImmutableMap<Integer, DigestType> WIRE_VALUE_TO_DIGEST_TYPE =
      Maps.uniqueIndex(Arrays.stream(DigestType.values()).iterator(), DigestType::getWireValue);

  /** Fetches a DigestType enumeration constant by its IANA assigned value. */
  public static Optional<DigestType> fromWireValue(int wireValue) {
    return Optional.ofNullable(WIRE_VALUE_TO_DIGEST_TYPE.get(wireValue));
  }

  /** Fetches a value in the range [0, 255] that encodes this DS digest type on the wire. */
  public int getWireValue() {
    return wireValue;
  }

  /** Returns the expected length in bytes of the signature. */
  public int getBytes() {
    return bytes;
  }
}
