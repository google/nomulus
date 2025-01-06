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

package google.registry.model.domain.fee;

import com.google.common.base.Ascii;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlValue;
import java.util.Locale;

/** A command name along with the launch phase and subphase it is to be executed in. */
public class FeeExtensionCommandDescriptor extends ImmutableObject {

  @XmlAttribute
  String phase;

  @XmlAttribute
  String subphase;

  @XmlValue
  String command;

  public String getPhase() {
    return phase;
  }

  public String getSubphase() {
    return subphase;
  }

  public String getUnparsedCommandName() {
    return command;
  }

  public CommandName getCommand() {
    // Require the xml string to be lowercase.
    if (command != null && command.toLowerCase(Locale.ENGLISH).equals(command)) {
      try {
        return CommandName.valueOf(Ascii.toUpperCase(command));
      } catch (IllegalArgumentException e) {
        // Swallow this and return UNKNOWN below because there's no matching CommandName.
      }
    }
    return CommandName.UNKNOWN;
  }

  public static FeeExtensionCommandDescriptor
      create(CommandName commandName, String phase, String subphase) {
    FeeExtensionCommandDescriptor commandDescriptor = new FeeExtensionCommandDescriptor();
    commandDescriptor.command = Ascii.toLowerCase(commandName.name());
    commandDescriptor.phase = phase;
    commandDescriptor.subphase = subphase;
    return commandDescriptor;
  }
}
