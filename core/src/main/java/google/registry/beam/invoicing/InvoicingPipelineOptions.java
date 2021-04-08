// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.invoicing;

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Description;

/** Custom options for running the invoicing pipeline. */
public interface InvoicingPipelineOptions extends RegistryPipelineOptions {

  @Description("The year and month we generate invoices for, in yyyy-MM format.")
  String getYearMonth();

  void setYearMonth(String value);

  @Description("The GCS bucket URL for invoices and detailed  reports to be uploaded.")
  String getBillingBucketUrl();

  void setBillingBucketUrl(String value);

  @Description("Filename prefix for the detailed reports.")
  String getInvoiceFilePrefix();

  void setInvoiceFilePrefix(String value);
}
