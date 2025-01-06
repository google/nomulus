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

package google.registry.xml;

import static com.google.common.base.Strings.isNullOrEmpty;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import javax.annotation.Nullable;
import org.joda.time.Period;

/** Adapter to use Joda {@link Period} when marshalling XML. */
public class PeriodAdapter extends XmlAdapter<String, Period> {

  @Nullable
  @Override
  public Period unmarshal(@Nullable String periodString) {
    return isNullOrEmpty(periodString) ? null : Period.parse(periodString);
  }

  @Nullable
  @Override
  public String marshal(@Nullable Period period) {
    return period == null ? null : period.toString();
  }
}
