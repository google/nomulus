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

package google.registry.whois;

import org.joda.time.DateTime;

/** Represents a WHOIS command request from a client. */
public interface WhoisCommand {

  /**
   * Executes a WHOIS query and returns the resultant data.
   *
   * @return An object representing the response to the WHOIS command.
   * @throws WhoisException If some error occured while executing the command.
   */
  WhoisResponse executeQuery(DateTime now) throws WhoisException;
}
