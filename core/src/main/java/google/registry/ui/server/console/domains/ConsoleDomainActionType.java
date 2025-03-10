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

import com.google.common.escape.Escaper;
import com.google.common.xml.XmlEscapers;
import com.google.gson.JsonElement;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;

/**
 * A type of EPP action to perform on domain(s), run by the {@link ConsoleBulkDomainAction}.
 *
 * <p>Each {@link BulkAction} defines the class that extends that action, including the EPP XML that
 * will be run and the permission required.
 */
public abstract class ConsoleDomainActionType {

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

  static ConsoleDomainActionType parseActionType(String bulkDomainAction, JsonElement jsonElement) {
    BulkAction bulkAction = BulkAction.valueOf(bulkDomainAction);
    try {
      return bulkAction.getActionClass().getConstructor(JsonElement.class).newInstance(jsonElement);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e); // shouldn't happen
    }
  }

  private static final Escaper XML_ESCAPER = XmlEscapers.xmlContentEscaper();

  private final String reason;

  public ConsoleDomainActionType(JsonElement jsonElement) {
    this.reason = jsonElement.getAsJsonObject().get("reason").getAsString();
  }

  /** Returns the full XML representing this action, including all substitutions. */
  public String getXmlContentsToRun(String domainName) {
    return fillSubstitutions(getXmlTemplate(), domainName);
  }

  /** Returns the permission necessary to successfully perform this action. */
  public abstract ConsolePermission getNecessaryPermission();

  /** Returns the type of history / audit logging object to save. */
  public abstract ConsoleUpdateHistory.Type getConsoleUpdateHistoryType();

  /** Returns the XML template contents for this action. */
  protected abstract String getXmlTemplate();

  /**
   * Fills out the default set of substitutions in the provided XML template.
   *
   * <p>Override this method if non-default substitutions are required.
   */
  protected String fillSubstitutions(String xmlTemplate, String domainName) {
    String xml = xmlTemplate;
    xml = replaceValue(xml, "DOMAIN_NAME", domainName);
    return replaceValue(xml, "REASON", reason);
  }

  private String replaceValue(String xml, String key, String value) {
    return xml.replaceAll("%" + key + "%", XML_ESCAPER.escape(value));
  }
}
