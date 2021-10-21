// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import google.registry.model.translators.CreateAutoTimestampTranslatorFactory;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * A timestamp that auto-updates when first saved to Datastore.
 *
 * @see CreateAutoTimestampTranslatorFactory
 */
public class CreateAutoTimestamp extends ImmutableObject implements UnsafeSerializable {

  DateTime timestamp;

  /** Returns the timestamp. */
  @Nullable
  public DateTime getTimestamp() {
    return timestamp;
  }

  public static CreateAutoTimestamp create(@Nullable DateTime timestamp) {
    CreateAutoTimestamp instance = new CreateAutoTimestamp();
    instance.timestamp = timestamp;
    return instance;
  }
}
