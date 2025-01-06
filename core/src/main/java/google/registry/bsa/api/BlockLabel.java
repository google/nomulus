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

package google.registry.bsa.api;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import java.util.List;

/**
 * A BSA label to block. New domains with matching second-level domain (SLD) will be denied
 * registration in TLDs enrolled with BSA.
 */
public record BlockLabel(String label, LabelType labelType, ImmutableSet<String> idnTables) {

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',').trimResults();

  public String serialize() {
    return JOINER.join(label(), labelType().name(), idnTables().stream().sorted().toArray());
  }

  public static BlockLabel deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    try {
      return create(
          items.get(0),
          LabelType.valueOf(items.get(1)),
          ImmutableSet.copyOf(items.subList(2, items.size())));
    } catch (NumberFormatException ne) {
      throw new IllegalArgumentException(text);
    }
  }

  public static BlockLabel create(String label, LabelType type, ImmutableSet<String> idnTables) {
    return new BlockLabel(label, type, idnTables);
  }

  public enum LabelType {
    CREATE,
    NEW_ORDER_ASSOCIATION,
    DELETE;
  }
}
