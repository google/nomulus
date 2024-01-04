// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.api;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.List;

/**
 * A domain name whose second-level domain (SLD) matches a BSA label but is not blocked. It may be
 * already registered, or on the TLD's reserve list.
 */
@AutoValue
public abstract class NonBlockedDomain {
  abstract String label();

  abstract String tld();

  abstract Reason reason();

  /** Reasons why a valid domain name cannot be blocked. */
  public enum Reason {
    REGISTERED,
    RESERVED,
    INVALID;
  }

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',');

  public String serialize() {
    return JOINER.join(label(), tld(), reason().name());
  }

  public static NonBlockedDomain deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    return of(items.get(0), items.get(1), Reason.INVALID.valueOf(items.get(2)));
  }

  public static NonBlockedDomain of(String label, String tld, Reason reason) {
    return new AutoValue_NonBlockedDomain(label, tld, reason);
  }
}
