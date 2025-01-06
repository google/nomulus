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

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * Converter for lists of String params that omits any empty strings.
 *
 * <p>JCommander automatically parses a comma-separated list well enough, but it parses the empty
 * list, e.g. "--foo=" as a list consisting solely of the empty string, which is not what we want.
 */
public class StringListParameter extends ParameterConverterValidator<List<String>> {

  @Override
  public List<String> convert(String value) {
    if (Strings.isNullOrEmpty(value)) {
      return ImmutableList.of();
    }
    return Splitter.on(',').trimResults().omitEmptyStrings().splitToList(value);
  }
}
