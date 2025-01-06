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

package google.registry.model.mark;

import google.registry.model.eppcommon.Address;

/**
 * Mark Holder/Owner Address
 *
 * <p>This class is embedded inside {@link CommonMarkContactFields} hold the address of a mark
 * contact or holder. The fields are all defined in parent class {@link Address}, but the subclass
 * is still necessary to pick up the mark namespace.
 *
 * @see CommonMarkContactFields
 */
public class MarkAddress extends Address {

  /** Builder for {@link MarkAddress}. */
  public static class Builder extends Address.Builder<MarkAddress> {}
}
