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

package google.registry.beam.billing;

import static com.google.common.truth.Truth.assertThat;

import google.registry.beam.billing.BillingEvent.BillingEventCoder;
import google.registry.beam.billing.BillingEvent.InvoiceGroupingKey;
import google.registry.beam.billing.BillingEvent.InvoiceGroupingKey.InvoiceGroupingKeyCoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.beam.sdk.coders.NullableCoder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BillingEvent} */
class BillingEventTest {

  private BillingEvent event;

  @BeforeEach
  void beforeEach() {
    event = createBillingEvent("", 5);
  }

  private static BillingEvent createBillingEvent(String pONumber, int years) {
    return BillingEvent.create(
        1,
        new DateTime(1508835963000L, DateTimeZone.UTC),
        new DateTime(1484870383000L, DateTimeZone.UTC),
        "myRegistrar",
        "12345-CRRHELLO",
        pONumber,
        "test",
        "RENEW",
        "example.test",
        "123456",
        years,
        "USD",
        20.5,
        "AUTO_RENEW SYNTHETIC");
  }

  @Test
  void testConvertBillingEvent_toCsv() {
    assertThat(event.toCsv())
        .isEqualTo(
            "1,2017-10-24 09:06:03 UTC,2017-01-19 23:59:43 UTC,myRegistrar,"
                + "12345-CRRHELLO,,test,RENEW,example.test,123456,5,USD,20.50,AUTO_RENEW");
  }

  @Test
  void testConvertBillingEvent_nonNullPoNumber_toCsv() {
    event = createBillingEvent("905610", 5);
    assertThat(event.toCsv())
        .isEqualTo(
            "1,2017-10-24 09:06:03 UTC,2017-01-19 23:59:43 UTC,myRegistrar,"
                + "12345-CRRHELLO,905610,test,RENEW,example.test,123456,5,USD,20.50,AUTO_RENEW");
  }

  @Test
  void testGenerateBillingEventFilename() {
    assertThat(event.toFilename("2017-10")).isEqualTo("invoice_details_2017-10_myRegistrar_test");
  }

  @Test
  void testGetInvoiceGroupingKey_fromBillingEvent() {
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.startDate()).isEqualTo("2017-10-01");
    assertThat(invoiceKey.endDate()).isEqualTo("2022-09-30");
    assertThat(invoiceKey.productAccountKey()).isEqualTo("12345-CRRHELLO");
    assertThat(invoiceKey.usageGroupingKey()).isEqualTo("myRegistrar");
    assertThat(invoiceKey.description()).isEqualTo("RENEW | TLD: test | TERM: 5-year");
    assertThat(invoiceKey.unitPrice()).isEqualTo(20.5);
    assertThat(invoiceKey.unitPriceCurrency()).isEqualTo("USD");
    assertThat(invoiceKey.poNumber()).isEmpty();
  }

  @Test
  void test_nonNullPoNumber() {
    event = createBillingEvent("905610", 5);
    assertThat(event.poNumber()).isEqualTo("905610");
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.poNumber()).isEqualTo("905610");
  }

  @Test
  void testConvertInvoiceGroupingKey_toCsv() {
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.toCsv(3L))
        .isEqualTo(
            "2017-10-01,2022-09-30,12345-CRRHELLO,61.50,USD,10125,1,PURCHASE,"
                + "myRegistrar,3,RENEW | TLD: test | TERM: 5-year,20.50,USD,");
  }

  @Test
  void testConvertInvoiceGroupingKey_zeroYears_toCsv() {
    event = createBillingEvent("", 0);
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.toCsv(3L))
        .isEqualTo(
            "2017-10-01,,12345-CRRHELLO,61.50,USD,10125,1,PURCHASE,"
                + "myRegistrar,3,RENEW | TLD: test | TERM: 0-year,20.50,USD,");
  }

  @Test
  void testInvoiceGroupingKeyCoder_deterministicSerialization() throws IOException {
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    InvoiceGroupingKeyCoder coder = InvoiceGroupingKeyCoder.of();
    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    coder.encode(invoiceKey, outStream);
    InputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
    assertThat(coder.decode(inStream)).isEqualTo(invoiceKey);
  }

  @Test
  void testBillingEventCoder_deterministicSerialization() throws IOException {
    NullableCoder<BillingEvent> coder = BillingEventCoder.ofNullable();
    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    coder.encode(event, outStream);
    InputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
    assertThat(coder.decode(inStream)).isEqualTo(event);
  }

  @Test
  void testGetDetailReportHeader() {
    assertThat(BillingEvent.getHeader())
        .isEqualTo(
            "id,billingTime,eventTime,registrarId,billingId,poNumber,tld,action,"
                + "domain,repositoryId,years,currency,amount,flags");
  }

  @Test
  void testGetOverallInvoiceHeader() {
    assertThat(InvoiceGroupingKey.invoiceHeader())
        .isEqualTo(
            "StartDate,EndDate,ProductAccountKey,Amount,AmountCurrency,BillingProductCode,"
                + "SalesChannel,LineItemType,UsageGroupingKey,Quantity,Description,UnitPrice,"
                + "UnitPriceCurrency,PONumber");
  }
}
