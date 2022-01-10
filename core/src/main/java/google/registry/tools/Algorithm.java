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

package google.registry.tools;

/**
 * Enumerates the various DNSSEC algorithms.
 *
 * <p>This represents all algorithms which have assigned numbers by IANA at the time of this
 * writing.
 *
 * <p>This list may be incomplete if new algorithm numbers have been assigned. The complete list can
 * be found here: http://www.iana.org/assignments/dns-sec-alg-numbers/dns-sec-alg-numbers.xhtml
 */
public enum Algorithm {
  // 0 Reserved.
  RSAMD5(1),
  DH(2),
  DSA(3),
  // 4 Reserved.
  RSASHA1(5),
  DSA_NSEC3_SHA1(6),
  RSASHA1_NSEC3_SHA1(7),
  RSASHA256(8),
  // 9 Reserved.
  RSASHA512(10),
  // 11 Reserved.
  ECC_GOST(12),
  ECDSAP256SHA256(13),
  ECDSAP384SHA384(14),
  ED25519(15),
  ED448(16),
  // 17-122 Unassigned.
  // 123-251 Reserved.
  INDIRECT(252),
  PRIVATEDNS(253),
  PRIVATEOID(254);
  // 255 Reserved.

  private final int wireValue;

  Algorithm(int wireValue) {
    this.wireValue = wireValue;
  }

  public static Algorithm fromWireValue(int wireValue) {
    for (Algorithm alg : Algorithm.values()) {
      if (alg.getWireValue() == wireValue) {
        return alg;
      }
    }
    return null;
  }

  /** Fetches a value in the range [0, 255] that encodes this DNSSEC algorithm on the wire. */
  public int getWireValue() {
    return wireValue;
  }
}
