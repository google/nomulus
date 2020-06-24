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

package google.registry.model.domain.fee06;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import java.util.List;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import org.joda.money.CurrencyUnit;

/** Version 0.6 of the fee extension that may be present on domain check commands. */
@XmlRootElement(name = "check")
public class FeeCheckCommandExtensionV06 extends ImmutableObject
    implements FeeCheckCommandExtension<
        FeeCheckCommandExtensionItemV06,
        FeeCheckResponseExtensionV06> {

  @XmlElement(name = "domain")
  List<FeeCheckCommandExtensionItemV06> items;

  @Override
  public CurrencyUnit getCurrency() {
    return null;  // This version of the fee extension doesn't specify a top-level currency.
  }

  @Override
  public ImmutableList<FeeCheckCommandExtensionItemV06> getItems() {
    return nullToEmptyImmutableCopy(items);
  }

  @Override
  public FeeCheckResponseExtensionV06 createResponse(
      ImmutableList<? extends FeeCheckResponseExtensionItem> items) {
    ImmutableList.Builder<FeeCheckResponseExtensionItemV06> builder = new ImmutableList.Builder<>();
    for (FeeCheckResponseExtensionItem item : items) {
      if (item instanceof FeeCheckResponseExtensionItemV06) {
        builder.add((FeeCheckResponseExtensionItemV06) item);
      }
    }
    return FeeCheckResponseExtensionV06.create(builder.build());
  }
}
