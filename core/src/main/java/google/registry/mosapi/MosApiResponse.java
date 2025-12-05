// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mosapi;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Represents known MoSAPI API result codes and their default messages.
 *
 * <p>The definitions for these codes can be found in the official ICANN MoSAPI Specification,
 * specifically in the 'Result Codes' section.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification</a>
 */
public enum MosApiResponse {
  DATE_DURATION_INVALID(
      "2011", "The difference between endDate and startDate is " + "more than 31 days"),
  DATE_ORDER_INVALID("2012", "The EndDate is before startDate"),
  START_DATE_SYNTAX_INVALID("2013", "StartDate syntax is invalid"),
  END_DATE_SYNTAX_INVALID("2014", "EndDate syntax is invalid");

  private final String code;
  private final String defaultMessage;

  private static final Map<String, MosApiResponse> CODE_MAP =
      Arrays.stream(values()).collect(Collectors.toMap(e -> e.code, Function.identity()));

  MosApiResponse(String code, String defaultMessage) {
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  public String getCode() {
    return code;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }

  // Returns the enum constant associated with the given result code string
  public static Optional<MosApiResponse> fromCode(String code) {

    return Optional.ofNullable(CODE_MAP.get(code));
  }
}
