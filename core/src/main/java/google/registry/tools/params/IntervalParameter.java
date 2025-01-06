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

import org.joda.time.DateTimeZone;
import org.joda.time.Interval;

/** Interval CLI parameter converter/validator. */
public final class IntervalParameter extends ParameterConverterValidator<Interval> {

  public IntervalParameter() {
    super("not an ISO-8601 interval (e.g. 2004-06-09T12:30:00Z/2004-07-10T13:30:00Z)");
  }

  @Override
  public Interval convert(String value) {
    // Interval.parse(null) creates an interval with both start and end times set to now.
    // Do something a little more reasonable.
    if (value == null) {
      throw new NullPointerException();
    }
    Interval interval = Interval.parse(value);
    // Interval does not have a way to set the time zone, so create a new interval with the
    // start and end times of the parsed interval converted to UTC.
    return new Interval(
        interval.getStart().withZone(DateTimeZone.UTC),
        interval.getEnd().withZone(DateTimeZone.UTC));
  }
}
