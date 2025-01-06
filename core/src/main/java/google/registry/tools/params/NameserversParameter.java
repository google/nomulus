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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.Integer.parseInt;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.util.DomainNameUtils;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Nameservers CLI parameter converter/validator.
 *
 * <p>This accepts a String containing a comma-delimited list of nameservers. Square bracket
 * notation can be used to expand multiple nameservers, e.g. "ns[1-2].googledomains.com" will be
 * expanded to "ns1.googledomains.com,ns2.googledomains.com".
 */
public final class NameserversParameter extends ParameterConverterValidator<Set<String>> {

  private static final Pattern FORMAT_BRACKETS = Pattern.compile("^(.+)\\[(\\d+)-(\\d+)\\](.+)$");

  public NameserversParameter() {
    super(
        "Must be a comma-delimited list of nameservers, "
            + "optionally using square bracket expansion e.g. foo[1-4].bar.baz notation");
  }

  @Override
  public Set<String> convert(String value) {
    if (Strings.isNullOrEmpty(value)) {
      return ImmutableSet.of();
    }
    return Splitter.on(',').trimResults().omitEmptyStrings().splitToList(value).stream()
        .flatMap(NameserversParameter::splitNameservers)
        .map(DomainNameUtils::canonicalizeHostname)
        .collect(toImmutableSet());
  }

  @VisibleForTesting
  static Stream<String> splitNameservers(String ns) {
    Matcher matcher = FORMAT_BRACKETS.matcher(ns);
    if (!matcher.matches()) {
      checkArgument(
          !ns.contains("[") && !ns.contains("]"), "Could not parse square brackets in %s", ns);
      return ImmutableList.of(ns).stream();
    }

    ImmutableList.Builder<String> nameservers = new ImmutableList.Builder<>();
    int start = parseInt(matcher.group(2));
    int end = parseInt(matcher.group(3));
    checkArgument(start <= end, "Number range [%s-%s] is invalid", start, end);
    for (int i = start; i <= end; i++) {
      nameservers.add(String.format("%s%d%s", matcher.group(1), i, matcher.group(4)));
    }
    return nameservers.build().stream();
  }
}
