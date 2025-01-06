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

package google.registry.model;

import java.util.Map;

/** Interface for objects that may be converted to JSON. */
public interface Jsonifiable {

  /**
   * Returns a JSON representation of this object.
   *
   * <p>The returned value must not return sensitive fields, so that it may be safe to return to
   * the client via an API response.
   */
  Map<String, Object> toJsonMap();
}
