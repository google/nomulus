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

package google.registry.ui.forms;

import static com.google.common.collect.Range.atMost;
import static com.google.common.collect.Range.closed;
import static com.google.common.collect.Range.singleton;
import static java.util.Locale.getISOCountries;

import com.google.common.collect.ImmutableSet;
import com.google.re2j.Pattern;

/** Utility class of {@link FormField} objects for validating EPP related things. */
public final class FormFields {

  private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n]+");
  /**
   * Form field that applies XML Schema Token cleanup to input.
   *
   * <p>This trims the input and collapses whitespace.
   *
   * @see <a href="http://www.w3.org/TR/xmlschema11-2/#token">XSD Datatypes - token</a>
   */
  public static final FormField<String, String> XS_TOKEN =
      FormField.named("xsToken")
          .emptyToNull()
          .trimmed()
          .transform(input -> input != null ? WHITESPACE.matcher(input).replaceAll(" ") : null)
          .build();

  /**
   * Form field that ensures input does not contain tabs, line feeds, or carriage returns.
   *
   * @see <a href="http://www.w3.org/TR/xmlschema11-2/#normalizedString">
   *     XSD Datatypes - normalizedString</a>
   */
  public static final FormField<String, String> XS_NORMALIZED_STRING =
      FormField.named("xsNormalizedString")
          .emptyToNull()
          .matches(Pattern.compile("[^\\t\\r\\n]*"), "Must not contain tabs or multiple lines.")
          .build();

  /**
   * Form field for +E164 phone numbers with a dot after the country prefix.
   *
   * @see <a href="http://tools.ietf.org/html/rfc5733#section-4">RFC 5733 - EPP - Formal Syntax</a>
   */
  public static final FormField<String, String> PHONE_NUMBER =
      XS_TOKEN.asBuilderNamed("phoneNumber")
          .range(atMost(17))
          .matches(Pattern.compile("(\\+[0-9]{1,3}\\.[0-9]{1,14})?"),
              "Must be a valid +E.164 phone number, e.g. +1.2125650000")
          .build();

  /** Form field for EPP client identifiers. */
  public static final FormField<String, String> CLID = XS_TOKEN.asBuilderNamed("clid")
      .range(closed(3, 16))
      .build();

  /** Form field for passwords (see pwType in epp.xsd). */
  public static final FormField<String, String> PASSWORD = XS_TOKEN.asBuilderNamed("password")
      .range(closed(6, 16))
      .build();

  /** Form field for non-empty tokens (see minToken in eppcom.xsd). */
  public static final FormField<String, String> MIN_TOKEN = XS_TOKEN.asBuilderNamed("minToken")
      .emptyToNull()
      .build();

  /** Form field for nameType (see rde-registrar/notification). */
  public static final FormField<String, String> NAME = XS_NORMALIZED_STRING.asBuilderNamed("name")
      .range(closed(1, 255))
      .build();

  /** Form field for {@code labelType} from {@code eppcom.xsd}. */
  public static final FormField<String, String> LABEL = XS_TOKEN.asBuilderNamed("label")
        .range(closed(1, 255))
        .build();

  /** Email address form field. */
  public static final FormField<String, String> EMAIL = XS_TOKEN.asBuilderNamed("email")
        .matches(Pattern.compile("[^@]+@[^@.]+\\.[^@]+"), "Please enter a valid email address.")
        .build();

  /** Two-letter ISO country code form field. */
  public static final FormField<String, String> COUNTRY_CODE =
      XS_TOKEN.asBuilderNamed("countryCode")
          .range(singleton(2))
          .uppercased()
          .in(ImmutableSet.copyOf(getISOCountries()))
          .build();

  /**
   * Ensure value is an EPP Repository Object IDentifier (ROID).
   *
   * @see <a href="http://tools.ietf.org/html/rfc5730#section-4.2">Shared Structure Schema</a>
   */
  public static final FormField<String, String> ROID = XS_TOKEN.asBuilderNamed("roid")
      .matches(Pattern.compile("(\\w|_){1,80}-\\w{1,8}"),
          "Please enter a valid EPP ROID, e.g. SH8013-REP")
      .build();

  private FormFields() {}
}
