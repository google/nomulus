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

package google.registry.tools.params;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

/** Base class for parameters that do both conversion and validation (reduces boilerplate). */
public abstract class ParameterConverterValidator<T>
    implements IStringConverter<T>, IParameterValidator {

  private final String messageForInvalid;

  ParameterConverterValidator() {
    this("Validation failed.");
  }

  ParameterConverterValidator(String messageForInvalid) {
    this.messageForInvalid = messageForInvalid;
  }

  @Override
  public abstract T convert(String arg0);  // Redefine so non-null package annotation kicks in.

  @Override
  public void validate(String name, String value) throws ParameterException {
    try {
      convert(value);
    } catch (IllegalArgumentException e) {
      throw new ParameterException(String.format("%s=%s %s", name, value, messageForInvalid), e);
    }
  }
}
