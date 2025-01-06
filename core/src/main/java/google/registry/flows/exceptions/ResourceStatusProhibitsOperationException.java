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

package google.registry.flows.exceptions;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.model.eppcommon.StatusValue;
import java.util.Set;

/** Resource status prohibits this operation. */
public class ResourceStatusProhibitsOperationException
    extends StatusProhibitsOperationException {
  public ResourceStatusProhibitsOperationException(Set<StatusValue> statuses) {
    super("Operation disallowed by status: " + formatStatusValues(statuses));
  }

  /** Returns a human-readable string listing the XML names of the given status values. */
  private static String formatStatusValues(Set<StatusValue> statuses) {
    ImmutableSet.Builder<String> statusXmlNames = new ImmutableSet.Builder<>();
    for (StatusValue status : statuses) {
      statusXmlNames.add(status.getXmlName());
    }
    return Joiner.on(", ").join(statusXmlNames.build());
  }
}
