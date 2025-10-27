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

package google.registry.model.domain.feestdv1;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.List;
import org.joda.money.CurrencyUnit;

/** Version 1.0 of the fee extension that may be present on domain check commands. */
@XmlRootElement(name = "check")
@XmlType(propOrder = {"currency", "items"})
public class FeeCheckCommandExtensionStdV1 extends ImmutableObject
    implements FeeCheckCommandExtension<
        FeeCheckCommandExtensionItemStdV1, FeeCheckResponseExtensionStdV1> {

  CurrencyUnit currency;

  @Override
  public CurrencyUnit getCurrency() {
    return currency;
  }

  @XmlElement(name = "command")
  List<FeeCheckCommandExtensionItemStdV1> items;

  @Override
  public ImmutableList<FeeCheckCommandExtensionItemStdV1> getItems() {
    return nullToEmptyImmutableCopy(items);
  }

  @Override
  public FeeCheckResponseExtensionStdV1 createResponse(
      ImmutableList<? extends FeeCheckResponseExtensionItem> items) {
    ImmutableList.Builder<FeeCheckResponseExtensionItemStdV1> builder =
        new ImmutableList.Builder<>();
    for (FeeCheckResponseExtensionItem item : items) {
      if (item instanceof FeeCheckResponseExtensionItemStdV1) {
        builder.add((FeeCheckResponseExtensionItemStdV1) item);
      }
    }
    return FeeCheckResponseExtensionStdV1.create(currency, builder.build());
  }
}
