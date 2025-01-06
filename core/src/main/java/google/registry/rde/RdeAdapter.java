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

package google.registry.rde;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import google.registry.xjc.rde.XjcRdeRrType;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/** Utility class that converts database objects to RDE XML objects. */
final class RdeAdapter {

  /** Create {@link XjcRdeRrType} with optional {@code client} attribute. */
  @Nullable
  @CheckForNull
  static XjcRdeRrType convertRr(@Nullable String value, @Nullable String client) {
    if (isNullOrEmpty(value)) {
      return null;
    }
    XjcRdeRrType rrType = new XjcRdeRrType();
    rrType.setValue(value);
    rrType.setClient(emptyToNull(client));
    return rrType;
  }
}
