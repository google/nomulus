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

package google.registry.flows;

import google.registry.model.eppoutput.EppOutput.ResponseOrGreeting;

/**
 * An Extensible Provisioning Protocol flow.
 *
 * <p>A "flow" is our word for a "command", since we've overloaded the word "command" to mean the
 * command objects and also the CLI operation classes.
 */
public interface Flow {

  /**
   * Executes an EPP "flow" and returns a response object (or in the specific case of the "hello"
   * flow a greeting object) that can be converted to XML and returned to the caller.
   *
   * <p>Flows should have {@link #run} called once per instance. If a flow needs to be retried, a
   * new instance should be created.
   *
   * <p>Flows should get all of their parameters via injection off of {@link FlowComponent}.
   */
  ResponseOrGreeting run() throws EppException;
}
