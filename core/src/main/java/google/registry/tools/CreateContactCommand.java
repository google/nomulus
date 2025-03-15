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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.params.PhoneNumberParameter;
import google.registry.tools.soy.ContactCreateSoyInfo;
import google.registry.util.StringGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

/** A command to create a new contact via EPP. */
@Parameters(separators = " =", commandDescription = "Create a new contact via EPP.")
final class CreateContactCommand extends MutatingEppToolCommand {

  // TODO(b/19016175): Expand to allow full suite of contact flows.
  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Parameter(
      names = {"-i", "--id"},
      description = "Contact ID.")
  private String id;

  @Parameter(
      names = {"-n", "--name"},
      description = "Contact name.")
  private String name;

  @Parameter(
      names = {"-o", "--org"},
      description = "Organization")
  private String org;

  @Parameter(
      names = "--street",
      description = "Street lines of address. Can take up to 3 lines.",
      variableArity = true)
  private List<String> street;

  @Parameter(
      names = "--city",
      description = "City of address.")
  private String city;

  @Parameter(
      names = "--state",
      description = "State of address.")
  private String state;

  @Parameter(
      names = {"-z", "--zip"},
      description = "Postal code of address.")
  private String zip;

  @Parameter(
      names = "--cc",
      description = "Country code of address.")
  private String cc;

  @Parameter(
      names = "--phone",
      description = "E.164 phone number, e.g. +1.2125650666",
      converter = PhoneNumberParameter.class,
      validateWith = PhoneNumberParameter.class)
  String phone;

  @Parameter(
      names = "--fax",
      description = "E.164 fax number, e.g. +1.2125650666",
      converter = PhoneNumberParameter.class,
      validateWith = PhoneNumberParameter.class)
  String fax;

  @Parameter(
      names = {"-e", "--email"},
      description = "Email address.")
  private String email;

  @Parameter(
      names = {"-p", "--password"},
      description = "Password. Optional, randomly generated if not provided.")
  private String password;

  @Inject
  @Named("base64StringGenerator")
  StringGenerator passwordGenerator;

  private static final int PASSWORD_LENGTH = 16;

  @Override
  protected void initMutatingEppToolCommand() {
    if (isNullOrEmpty(password)) {
      password = passwordGenerator.createString(PASSWORD_LENGTH);
    }
    checkArgument(street == null || street.size() <= 3,
        "Addresses must contain at most 3 street lines.");

    setSoyTemplate(ContactCreateSoyInfo.getInstance(), ContactCreateSoyInfo.CONTACTCREATE);
    addSoyRecord(clientId, new SoyMapData(
        "id", id,
        "name", name,
        "org", org,
        "street", street,
        "city", city,
        "state", state,
        "zip", zip,
        "cc", cc,
        "phone", phone,
        "fax", fax,
        "email", email,
        "password", password));
  }
}
