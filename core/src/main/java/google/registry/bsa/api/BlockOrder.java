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
import java.util.List;

/**
 * A BSA order, which are needed when communicating with the BSA API while processing downloaded
 * block lists.
 */
public record BlockOrder(long orderId, OrderType orderType) {

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',');

  public String serialize() {
    return JOINER.join(orderId(), orderType().name());
  }

  public static BlockOrder deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    try {
      return create(Long.parseLong(items.get(0)), OrderType.valueOf(items.get(1)));
    } catch (NumberFormatException ne) {
      throw new IllegalArgumentException(text);
    }
  }

  public static BlockOrder create(long orderId, OrderType orderType) {
    return new BlockOrder(orderId, orderType);
  }

  public enum OrderType {
    CREATE,
    DELETE;
  }
}
