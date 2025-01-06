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

import com.google.common.collect.ImmutableList;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import org.joda.money.CurrencyUnit;

/**
 * Interface for domain check response fee extensions. The check extension will contain some number
 * of items requesting the fees for particular commands and domains. For some versions of the fee
 * extension, the currency is also specified here; for other versions it is contained in the
 * individual items.
 */
public interface FeeCheckResponseExtension<F extends FeeCheckResponseExtensionItem>
    extends ResponseExtension {

  /**
   * If currency is not supported at the top level of Check responses for this version of the fee
   * extension, this function has not effect.
   */
  void setCurrencyIfSupported(CurrencyUnit currency);

  ImmutableList<F> getItems();
}
