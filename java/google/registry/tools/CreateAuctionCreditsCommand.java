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

package google.registry.tools;

import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_UNDERSCORE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.registry.Registries.assertTldExists;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.model.billing.RegistrarCredit;
import google.registry.model.billing.RegistrarCredit.CreditType;
import google.registry.model.billing.RegistrarCreditBalance;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.joda.money.BigMoney;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Command for creating new auction credits based on a CSV file from Pool.
 *
 * <p>The CSV file from the auction provider uses double-quotes around every field, so in order to
 * extract the raw field value we strip off the quotes after splitting each line by commas.  We are
 * using a simple parsing strategy that does not support embedded quotation marks, commas, or
 * newlines.
 *
 * <p>Example file format:
 *
 * <pre>
 * "Affiliate","DomainName","Email","BidderId","BidderStatus","UpdatedAt",
 *      "SalePrice","Commissions","CurrencyCode"
 * "reg1","foo.xn--q9jyb4c","email1@example.com","???_300","INACTIVE","4/3/2014 7:13:09 PM",
 *      "1000.0000","0.0000","JPY"
 * "reg2","foo.xn--q9jyb4c","email2@example.net","???_64","WIN","4/3/2014 7:13:09 PM",
 *      "1000.0000","40.0000","JPY"
 * </pre>
 *
 * <p>We only care about three fields: 1) the "Affiliate" field which corresponds to the registrar
 * clientId stored in datastore, and which we use to determine which registrar gets the credit, 2)
 * the "Commissions" field which contains the amount of the auction credit (as determined by logic
 * on the auction provider's side, see the Finance Requirements Doc for more information), and 3)
 * the "CurrencyCode" field, which we validate matches the TLD-wide currency for this TLD.
 */
// TODO(b/16009815): Switch this file to using a real CSV parser.
@Parameters(separators = " =", commandDescription = "Create new auction credits based on CSV")
final class CreateAuctionCreditsCommand extends MutatingCommand {

  @Parameter(
      names = "--input_file",
      description = "CSV file for the Pool.com commissions report",
      required = true)
  private Path inputFile;

  @Parameter(
      names = {"-t", "--tld"},
      description = "The TLD corresponding to this commissions report",
      required = true)
  private String tld;

  @Parameter(
      names = "--effective_time",
      description = "The time at which these auction credits should become effective",
      required = true)
  private DateTime effectiveTime;

  /** Enum containing the headers we expect in the Pool.com CSV file, in order. */
  private enum CsvHeader {
    AFFILIATE,
    DOMAIN_NAME,
    EMAIL,
    BIDDER_ID,
    BIDDER_STATUS,
    UPDATED_AT,
    SALE_PRICE,
    COMMISSIONS,
    CURRENCY_CODE;

    public static List<String> getHeaders() {
      return FluentIterable.from(values())
          .transform(new Function<CsvHeader, String>() {
              @Override
              public String apply(CsvHeader header) {
                // Returns the name of the header as it appears in the CSV file.
                return UPPER_UNDERSCORE.to(UPPER_CAMEL, header.name());
              }})
          .toList();
    }
  }

  private static final Pattern QUOTED_STRING = Pattern.compile("\"(.*)\"");

  /** Helper function to unwrap a quoted string, failing if the string is not quoted. */
  private static final Function<String, String> UNQUOTER = new Function<String, String>() {
    @Override
    public String apply(String input) {
      Matcher matcher = QUOTED_STRING.matcher(input);
      checkArgument(matcher.matches(), "Input not quoted");
      return matcher.group(1);
    }};

  /** Returns the input string of quoted CSV values split into the list of unquoted values. */
  private static List<String> splitCsvLine(String line) {
    return FluentIterable.from(Splitter.on(',').split(line)).transform(UNQUOTER).toList();
  }

  @Override
  protected void init() throws Exception {
    assertTldExists(tld);
    ImmutableMultimap<Registrar, BigMoney> creditMap = parseCreditsFromCsv(inputFile, tld);
    stageCreditCreations(creditMap);
  }

  /**
   * Parses the provided CSV file of data from the auction provider and returns a multimap mapping
   * each registrar to the collection of auction credit amounts from this TLD's auctions that should
   * be awarded to this registrar, and validating that every credit amount's currency is in the
   * specified TLD-wide currency.
   */
  private static ImmutableMultimap<Registrar, BigMoney> parseCreditsFromCsv(
      Path csvFile, String tld) throws IOException {
    List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
    checkArgument(CsvHeader.getHeaders().equals(splitCsvLine(lines.get(0))),
        "Expected CSV header line not present");
    ImmutableMultimap.Builder<Registrar, BigMoney> builder = new ImmutableMultimap.Builder<>();
    for (String line : Iterables.skip(lines, 1)) {
      List<String> fields = splitCsvLine(line);
      checkArgument(CsvHeader.getHeaders().size() == fields.size(), "Wrong number of fields");
      try {
        String registrarId = fields.get(CsvHeader.AFFILIATE.ordinal());
        Registrar registrar = checkNotNull(
            Registrar.loadByClientId(registrarId), "Registrar %s not found", registrarId);
        CurrencyUnit tldCurrency = Registry.get(tld).getCurrency();
        CurrencyUnit currency = CurrencyUnit.of((fields.get(CsvHeader.CURRENCY_CODE.ordinal())));
        checkArgument(tldCurrency.equals(currency),
            "Credit in wrong currency (%s should be %s)", currency, tldCurrency);
        // We use BigDecimal and BigMoney to preserve fractional currency units when computing the
        // total amount of each credit (since auction credits are percentages of winning bids).
        BigDecimal creditAmount = new BigDecimal(fields.get(CsvHeader.COMMISSIONS.ordinal()));
        BigMoney credit = BigMoney.of(currency, creditAmount);
        builder.put(registrar, credit);
      } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
        throw new IllegalArgumentException("Error in line: " + line, e);
      }
    }
    return builder.build();
  }

  /**
   * Stages the creation of RegistrarCredit and RegistrarCreditBalance instances for each
   * registrar in the provided multimap of credit amounts by registrar.  The balance instance
   * created is the total of all the credit amounts for a given registrar.
   */
  private void stageCreditCreations(ImmutableMultimap<Registrar, BigMoney> creditMap) {
    DateTime now = DateTime.now(UTC);
    CurrencyUnit currency = Registry.get(tld).getCurrency();
    for (Registrar registrar : creditMap.keySet()) {
      // Use RoundingMode.UP to be nice and give registrars the extra fractional units.
      Money totalAmount =
          BigMoney.total(currency, creditMap.get(registrar)).toMoney(RoundingMode.UP);
      System.out.printf("Total auction credit balance for %s: %s\n",
          registrar.getClientId(), totalAmount);

      // Create the actual credit and initial credit balance.
      RegistrarCredit credit = new RegistrarCredit.Builder()
          .setParent(registrar)
          .setType(CreditType.AUCTION)
          .setCreationTime(now)
          .setCurrency(currency)
          .setTld(tld)
          .build();
      RegistrarCreditBalance creditBalance = new RegistrarCreditBalance.Builder()
          .setParent(credit)
          .setEffectiveTime(effectiveTime)
          .setWrittenTime(now)
          .setAmount(totalAmount)
          .build();
      stageEntityChange(null, credit);
      stageEntityChange(null, creditBalance);
    }
  }
}
