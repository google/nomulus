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

package google.registry.tldconfig.idn;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import java.util.Optional;

@Immutable
abstract class LanguageValidator {

  /** A registry of all known language validators keyed by their language code. */
  private static final ImmutableMap<String, LanguageValidator> LANGUAGE_VALIDATORS =
      ImmutableMap.of("ja", new JapaneseLanguageValidator());

  /** Return the language validator for the given language code (if one exists). */
  static Optional<LanguageValidator> get(String language) {
    return Optional.ofNullable(LANGUAGE_VALIDATORS.get(language));
  }

  /** Returns true if the label meets the context rules for this language. */
  abstract boolean isValidLabelForLanguage(String label);
}
