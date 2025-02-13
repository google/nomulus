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

package google.registry.ui.server.console.domains;

import com.google.common.collect.ImmutableMap;
import com.google.common.escape.Escaper;
import com.google.common.xml.XmlEscapers;
import com.google.gson.JsonElement;
import google.registry.model.console.ConsolePermission;
import java.util.Map;

/**
 * A type of EPP action to perform on domain(s), run by the {@link ConsoleBulkDomainAction}.
 *
 * <p>Each {@link BulkAction} defines the class that implements that action, including the EPP XML
 * that will be run and the permission required.
 */
public interface ConsoleDomainActionType {

  enum BulkAction {
    DELETE(ConsoleBulkDomainDeleteActionType.class),
    SUSPEND(ConsoleBulkDomainSuspendActionType.class),
    UNSUSPEND(ConsoleBulkDomainUnsuspendActionType.class);

    private final Class<? extends ConsoleDomainActionType> actionClass;

    BulkAction(Class<? extends ConsoleDomainActionType> actionClass) {
      this.actionClass = actionClass;
    }

    public Class<? extends ConsoleDomainActionType> getActionClass() {
      return actionClass;
    }
  }

  Escaper XML_ESCAPER = XmlEscapers.xmlContentEscaper();

  static String fillSubstitutions(String xmlTemplate, ImmutableMap<String, String> replacements) {
    String xml = xmlTemplate;
    for (Map.Entry<String, String> entry : replacements.entrySet()) {
      xml = xml.replaceAll("%" + entry.getKey() + "%", XML_ESCAPER.escape(entry.getValue()));
    }
    return xml;
  }

  String getXmlContentsToRun(String domainName);

  ConsolePermission getNecessaryPermission();

  static ConsoleDomainActionType parseActionType(String bulkDomainAction, JsonElement jsonElement) {
    BulkAction bulkAction = BulkAction.valueOf(bulkDomainAction);
    try {
      return bulkAction.getActionClass().getConstructor(JsonElement.class).newInstance(jsonElement);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e); // shouldn't happen
    }
  }
}
