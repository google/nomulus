// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.pricing;

import static com.google.common.base.Preconditions.checkArgument;

import com.googlecode.objectify.annotation.Embed;

import google.registry.model.ImmutableObject;
import google.registry.model.Buildable;

import org.joda.money.Money;

import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

@Embed
public class DomainPrices extends ImmutableObject{

    Money createPrice;

    Money renewPrice;

    public Map<String, Money> toMap() {
        Map<String, Money> retmap = new HashMap<String, Money>();
        retmap.put("createPrice", createPrice);
        retmap.put("renewPrice", renewPrice);

        return retmap;
    }

    public Money getCreatePrice() {
        return createPrice;
    }
    public Money getRenewPrice() {
        return renewPrice;
    }

    public Builder builder() {

        return new Builder(this);
    }

    public static class Builder extends Buildable.Builder<DomainPrices> {
        public Builder() {
            super();
        }

        public Builder(DomainPrices instance) {
            super(instance);
        }

        public Builder setCreatePrice(Money createPrice) {
            getInstance().createPrice = createPrice;
            return this;
        }
        public Builder setRenewPrice(Money renewPrice) {
            getInstance().renewPrice = renewPrice;
            return this;
        }

        public Builder setIfNotNullCreatePrice(Money createPrice) {
            if(createPrice != null) {
                getInstance().createPrice = createPrice;
            }
            return this;
        }
        public Builder setIfNotNullRenewPrice(Money renewPrice) {
            if(renewPrice != null) {
                getInstance().renewPrice = renewPrice;
            }
            return this;
        }

        public Builder setFieldsFromMap(Map<String, Money> priceMap) {
            getInstance().createPrice = priceMap.get("createPrice");
            getInstance().renewPrice = priceMap.get("renewPrice");

            return this;
        }


        public DomainPrices build() {
            final DomainPrices instance = getInstance();

            checkArgument((instance.getCreatePrice() != null), "createPrice cannot be null");
            checkArgument((instance.getCreatePrice().isPositive()), "createPrice must be greater than 0");
            checkArgument((instance.getRenewPrice() != null), "renewPrice cannot be null");
            checkArgument((instance.getRenewPrice().isPositive()), "renewPrice must be greater than 0");

            return super.build();
        }
    }





}
 
