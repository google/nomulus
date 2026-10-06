// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.params;

import com.google.common.primitives.Longs;
import java.time.Instant;
import java.time.OffsetDateTime;

/** {@linkplain Instant} CLI parameter converter/validator. Can be ISO or millis from epoch. */
public final class InstantParameter extends ParameterConverterValidator<Instant> {

  public InstantParameter() {
    super("not an ISO-8601 timestamp (or millis from epoch)");
  }

  @Override
  public Instant convert(String value) {
    Long millis = Longs.tryParse(value);
    if (millis != null) {
      return Instant.ofEpochMilli(millis);
    }
    return OffsetDateTime.parse(value).toInstant();
  }
}
