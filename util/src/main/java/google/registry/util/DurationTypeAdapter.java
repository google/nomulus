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

package google.registry.util;

import java.io.IOException;
import java.time.Duration;

/** GSON type adapter for {@link Duration} objects. */
public class DurationTypeAdapter extends StringBaseTypeAdapter<Duration> {

  @Override
  protected Duration fromString(String stringValue) throws IOException {
    return Duration.parse(stringValue);
  }
}
