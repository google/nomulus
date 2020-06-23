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

package google.registry.model.domain.fee;

import com.google.common.collect.ImmutableList;
import google.registry.model.eppinput.EppInput.CommandExtension;
import org.joda.money.CurrencyUnit;

/**
 * Interface for domain check command fee extensions. The check extension will contain some number
 * of items requesting the fees for particular commands and domains. For some versions of the fee
 * extension, the currency is also specified here; for other versions it is contained in the
 * individual items.
 *
 * @type C the type of extension item used by this command (e.g. v6 items for a v6 extension)
 * @type R the type of response returned for for this command (e.g. v6 responses for a v6 extension)
 */
public interface FeeCheckCommandExtension<
        C extends FeeCheckCommandExtensionItem,
        R extends FeeCheckResponseExtension<?>>
    extends CommandExtension {

  /**
   * Three-character ISO4217 currency code.
   *
   * <p>Returns null if this version of the fee extension doesn't specify currency at the top level.
   */
  CurrencyUnit getCurrency();

  ImmutableList<C> getItems();

  R createResponse(ImmutableList<? extends FeeCheckResponseExtensionItem> items);
}
