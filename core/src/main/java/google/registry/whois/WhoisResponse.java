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

package google.registry.whois;

import org.joda.time.DateTime;

/** Representation of a WHOIS query response. */
public interface WhoisResponse {

  /**
   * Returns the WHOIS response.
   *
   * @param preferUnicode if {@code false} will cause the output to be converted to ASCII whenever
   *     possible; for example, converting IDN hostname labels to punycode. However certain things
   *     (like a domain registrant name with accent marks) will be returned "as is". If the WHOIS
   *     client has told us they're able to receive UTF-8 (such as with HTTP) then this field should
   *     be set to {@code true}.
   * @param disclaimer text to show at bottom of output
   */
  WhoisResponseResults getResponse(boolean preferUnicode, String disclaimer);

  /** Returns the time at which this response was created. */
  DateTime getTimestamp();

  /** A wrapper class for the plaintext response of a WHOIS command and its number of results. */
  record WhoisResponseResults(String plainTextOutput, int numResults) {

    static WhoisResponseResults create(String plainTextOutput, int numResults) {
      return new WhoisResponseResults(plainTextOutput, numResults);
    }
  }
}
