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

package google.registry.tools.params;

import org.joda.time.Duration;
import org.joda.time.Period;

/** Duration CLI parameter converter/validator. */
public final class DurationParameter extends ParameterConverterValidator<Duration> {

  public DurationParameter() {
    super("not an ISO-8601 duration (e.g. PT36H)");
  }

  @Override
  public Duration convert(String value) {
    return Period.parse(value).toStandardDuration();
  }
}
