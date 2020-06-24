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

package google.registry.model.domain.fee12;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import java.util.List;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;

/** Version 0.12 of the fee extension that may be present on domain check commands. */
@XmlRootElement(name = "check")
@XmlType(propOrder = {"currency", "items"})
public class FeeCheckCommandExtensionV12 extends ImmutableObject
    implements FeeCheckCommandExtension<
        FeeCheckCommandExtensionItemV12,
        FeeCheckResponseExtensionV12> {

  CurrencyUnit currency;

  @Override
  public CurrencyUnit getCurrency() {
    return currency;
  }

  @XmlElement(name = "command")
  List<FeeCheckCommandExtensionItemV12> items;

  @Override
  public ImmutableList<FeeCheckCommandExtensionItemV12> getItems() {
    return nullToEmptyImmutableCopy(items);
  }

  @Override
  public FeeCheckResponseExtensionV12 createResponse(
      ImmutableList<? extends FeeCheckResponseExtensionItem> items) {
    ImmutableList.Builder<FeeCheckResponseExtensionItemV12> builder = new ImmutableList.Builder<>();
    for (FeeCheckResponseExtensionItem item : items) {
      if (item instanceof FeeCheckResponseExtensionItemV12) {
        builder.add((FeeCheckResponseExtensionItemV12) item);
      }
    }
    return FeeCheckResponseExtensionV12.create(currency, builder.build());
  }
}
