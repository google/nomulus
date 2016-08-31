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

package google.registry.flows.domain;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.index.DomainApplicationIndex.loadActiveApplicationsByDomainName;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteTld;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainApplicationSubject.assertAboutApplications;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.ResourceCreateFlow.ResourceAlreadyExistsException;
import google.registry.flows.ResourceFlow.BadCommandForRegistryPhaseException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.domain.BaseDomainCreateFlow.AcceptedTooLongAgoException;
import google.registry.flows.domain.BaseDomainCreateFlow.ClaimsPeriodEndedException;
import google.registry.flows.domain.BaseDomainCreateFlow.ExpiredClaimException;
import google.registry.flows.domain.BaseDomainCreateFlow.InvalidTcnIdChecksumException;
import google.registry.flows.domain.BaseDomainCreateFlow.InvalidTrademarkValidatorException;
import google.registry.flows.domain.BaseDomainCreateFlow.MalformedTcnIdException;
import google.registry.flows.domain.BaseDomainCreateFlow.MaxSigLifeNotSupportedException;
import google.registry.flows.domain.BaseDomainCreateFlow.MissingClaimsNoticeException;
import google.registry.flows.domain.BaseDomainCreateFlow.UnexpectedClaimsNoticeException;
import google.registry.flows.domain.BaseDomainCreateFlow.UnsupportedMarkTypeException;
import google.registry.flows.domain.DomainApplicationCreateFlow.LandrushApplicationDisallowedDuringSunriseException;
import google.registry.flows.domain.DomainApplicationCreateFlow.NoticeCannotBeUsedWithSignedMarkException;
import google.registry.flows.domain.DomainApplicationCreateFlow.SunriseApplicationDisallowedDuringLandrushException;
import google.registry.flows.domain.DomainApplicationCreateFlow.UncontestedSunriseApplicationBlockedInLandrushException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.Base64RequiredForEncodedSignedMarksException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LaunchPhaseMismatchException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedException;
import google.registry.flows.domain.DomainFlowUtils.NoMarksFoundMatchingDomainException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkCertificateExpiredException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkCertificateInvalidException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkCertificateNotYetValidException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkCertificateRevokedException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkCertificateSignatureException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkEncodingErrorException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkParsingErrorException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkRevokedErrorException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarkSignatureException;
import google.registry.flows.domain.DomainFlowUtils.SignedMarksMustBeEncodedException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.TooManySignedMarksException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.LrpToken;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.RegistryConfigRule;
import google.registry.tmch.TmchCertificateAuthority;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link DomainApplicationCreateFlow}. */
public class DomainApplicationCreateFlowTest
    extends ResourceFlowTestCase<DomainApplicationCreateFlow, DomainApplication> {

  private static final String CLAIMS_KEY = "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001";

  @Rule
  public final RegistryConfigRule configRule = new RegistryConfigRule();

  /** This is the id of the SMD stored in "domain_create_sunrise_encoded_signed_mark.xml". */
  public static final String SMD_ID = "0000001761376042759136-65535";

  private ReservedList createReservedList() {
    return persistReservedList(
        "tld-reserved",
        "testandvalidate,FULLY_BLOCKED",
        "test---validate,ALLOWED_IN_SUNRISE");
  }

  @Before
  public void setUp() throws Exception {
    setEppInput("domain_create_sunrise_encoded_signed_mark.xml");
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder().setReservedLists(createReservedList()).build());
    inject.setStaticField(TmchCertificateAuthority.class, "clock", clock);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
  }

  /** Create host and contact entries for testing. */
  private void persistContactsAndHosts() {
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.net", i));
    }
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String responseXmlFile, boolean sunriseApplication)
      throws Exception {
    doSuccessfulTest(responseXmlFile, sunriseApplication, 1, null, null);
  }

  private void doSuccessfulTest(
      String responseXmlFile, boolean sunriseApplication, int years)
      throws Exception {
    doSuccessfulTest(responseXmlFile, sunriseApplication, years, null, null);
  }

  private void doSuccessfulTest(
      String responseXmlFile,
      boolean sunriseApplication,
      String feeExtensionVersion,
      String feeExtensionNamespace) throws Exception {
    doSuccessfulTest(
        responseXmlFile, sunriseApplication, 1, feeExtensionVersion, feeExtensionNamespace);
  }

  private void doSuccessfulTest(
      String responseXmlFile,
      boolean sunriseApplication,
      int years, String feeExtensionVersion,
      String feeExtensionNamespace) throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        (feeExtensionVersion == null)
          ? readFile(responseXmlFile)
          : readFile(
              responseXmlFile,
              ImmutableMap.of("FEE_VERSION", feeExtensionVersion, "FEE_NS", feeExtensionNamespace)),
        "epp.response.extension.creData.applicationID",
        "epp.response.resData.creData.crDate");
    // Check that the domain application was created and persisted with a history entry.
    // We want the one with the newest creation time, but lacking an index we need this code.
    List<DomainApplication> applications = ofy().load().type(DomainApplication.class).list();
    Collections.sort(applications, new Comparator<DomainApplication>() {
      @Override
      public int compare(DomainApplication a, DomainApplication b) {
        return a.getCreationTime().compareTo(b.getCreationTime());
      }});
    assertAboutApplications().that(getLast(applications))
        .hasFullyQualifiedDomainName(getUniqueIdFromCommand()).and()
        .hasNumEncodedSignedMarks(sunriseApplication ? 1 : 0).and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_APPLICATION_CREATE).and()
        .hasPeriodYears(years);
    assertNoBillingEvents();
    assertThat(loadActiveApplicationsByDomainName(getUniqueIdFromCommand(), clock.nowUtc()))
        .containsExactlyElementsIn(applications);
    assertEppResourceIndexEntityFor(getLast(applications));
  }

  private void runSuperuserFlow(String filename) throws Exception {
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile(filename),
        "epp.response.extension.creData.applicationID",
        "epp.response.resData.creData.crDate",
        "epp.response.extension.creData.phase");
  }

  @Test
  public void testDryRun_sunrushApplication() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    dryRunFlowAssertResponse(readFile("domain_create_sunrush_encoded_signed_mark_response.xml"),
        "epp.response.extension.creData.applicationID", "epp.response.resData.creData.crDate");
  }

  @Test
  public void testFailure_signedMarkCorrupt() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark_corrupt.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(SignedMarkParsingErrorException.class);
    runFlow();
  }

  @Test
  public void testFailure_signedMarkCertificateRevoked() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark_revoked_cert.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(SignedMarkCertificateRevokedException.class);
    runFlow();
  }

  @Test
  public void testFailure_signedMarkCertificateExpired() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    clock.setTo(DateTime.parse("2022-01-01"));
    clock.setTo(DateTime.parse("2022-01-01"));
    thrown.expect(SignedMarkCertificateExpiredException.class);
    runFlow();
  }

  @Test
  public void testFailure_signedMarkCertificateNotYetValid() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    clock.setTo(DateTime.parse("2012-07-26T00:01:00Z"));
    clock.setTo(DateTime.parse("2012-07-22T00:01:00Z"));
    thrown.expect(SignedMarkCertificateNotYetValidException.class);
    runFlow();
  }

  @Test
  @Ignore("I'm not sure how to get this to throw without creating a custom CA / certs")
  public void testFailure_signedMarkCertificateCorrupt() throws Exception {
    configRule.useTmchProdCert();
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark_certificate_corrupt.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(SignedMarkCertificateInvalidException.class);
    runFlow();
  }

  @Test
  public void testFailure_signedMarkCertificateSignature() throws Exception {
    configRule.useTmchProdCert();
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(SignedMarkCertificateSignatureException.class);
    runFlow();
  }

  @Test
  public void testFailure_signedMarkSignature() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark_signature_corrupt.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(SignedMarkSignatureException.class);
    runFlow();
  }

  @Test
  public void testDryRun_landrushApplicationInSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    dryRunFlowAssertResponse(readFile("domain_create_sunrush_response.xml"),
        "epp.response.extension.creData.applicationID", "epp.response.resData.creData.crDate");
  }

  @Test
  public void testDryRun_landrushWithClaims() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    dryRunFlowAssertResponse(readFile("domain_create_sunrush_response_claims.xml"),
        "epp.response.extension.creData.applicationID", "epp.response.resData.creData.crDate");
  }

  @Test
  public void testSuccess_sunrushApplication() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_encoded_signed_mark_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_sunrushApplicationReservedAllowedInSunrise() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_allowedinsunrise.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_allowedinsunrise_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_smdTrumpsClaimsList() throws Exception {
    persistClaimsList(ImmutableMap.of("test-validate", CLAIMS_KEY));
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_encoded_signed_mark_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_landrushApplicationInSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_response.xml", false);
  }

  @Test
  public void testFailure_landrushApplicationReservedAllowedInSunrise() throws Exception {
    thrown.expect(DomainReservedException.class);
    createTld("tld", TldState.SUNRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(ImmutableSet.of(createReservedList())).build());
    setEppInput("domain_create_landrush_allowedinsunrise.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationPremiumBlocked() throws Exception {
    thrown.expect(PremiumNameBlockedException.class);
    createTld("example", TldState.SUNRUSH);
    setEppInput("domain_create_landrush_premium.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    // Modify the Registrar to block premium names.
    persistResource(Registrar.loadByClientId("TheRegistrar").asBuilder()
        .setBlockPremiumNames(true)
        .build());
    runFlow();
  }

  @Test
  public void testFailure_landrushFeeNotProvidedOnPremiumName() throws Exception {
    thrown.expect(FeesRequiredForPremiumNameException.class);
    createTld("example", TldState.SUNRUSH);
    setEppInput("domain_create_landrush_premium.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_landrushWithClaimsInSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_response_claims.xml", false);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasLaunchNotice(LaunchNotice.create(
            "370d0b7c9223372036854775807",
            "tmch",
            DateTime.parse("2010-08-16T09:00:00.0Z"),
            DateTime.parse("2009-08-16T09:00:00.0Z")))
        .and()
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_landrushWithoutClaimsInSunrush_forClaimsListName_afterClaimsPeriodEnd()
      throws Exception {
    createTld("tld", TldState.SUNRUSH);
    persistClaimsList(ImmutableMap.of("test-validate", CLAIMS_KEY));
    setEppInput("domain_create_sunrush.xml");
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setClaimsPeriodEnd(clock.nowUtc())
        .build());
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_response.xml", false);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasLaunchNotice(null)
        .and()
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_landrushApplication() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false);
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_v06() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.6", "fee");
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_v11() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.11", "fee11");
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_v12() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.12", "fee12");
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_withDefaultAttributes_v06() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.6", "fee");
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_withDefaultAttributes_v11() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.11", "fee11");
  }

  @Test
  public void testSuccess_landrushApplicationWithFee_withDefaultAttributes_v12() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_fee_response.xml", false, "0.12", "fee12");
  }

  @Test
  public void testFailure_landrushApplicationWithRefundableFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput("domain_create_landrush_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithRefundableFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_landrush_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithRefundableFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_landrush_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithGracePeriodFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_landrush_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithGracePeriodFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_landrush_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithGracePeriodFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_landrush_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithAppliedFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput("domain_create_landrush_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithAppliedFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput("domain_create_landrush_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    runFlow();
  }

  @Test
  public void testFailure_landrushApplicationWithAppliedFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    setEppInput("domain_create_landrush_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    runFlow();
  }

  @Test
  public void testSuccess_landrushWithClaims() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_landrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response_claims.xml", false);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasLaunchNotice(LaunchNotice.create(
            "370d0b7c9223372036854775807",
            "tmch",
            DateTime.parse("2010-08-16T09:00:00.0Z"),
            DateTime.parse("2009-08-16T09:00:00.0Z")))
        .and()
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_landrushWithoutClaims_forClaimsListName_afterClaimsPeriodEnd()
      throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistClaimsList(ImmutableMap.of("test-validate", CLAIMS_KEY));
    setEppInput("domain_create_landrush.xml");
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setClaimsPeriodEnd(clock.nowUtc())
        .build());
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasLaunchNotice(null)
        .and()
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testSuccess_maxNumberOfNameservers() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_13_nameservers.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrush_response.xml", false);
  }

  @Test
  public void testSuccess_encodedSignedMarkWithWhitespace() throws Exception {
    setEppInput("domain_create_sunrise_encoded_signed_mark_with_whitespace.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
  }

  @Test
  public void testSuccess_atMarkCreationTime() throws Exception {
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
  }

  @Test
  public void testSuccess_smdRevokedInFuture() throws Exception {
    SignedMarkRevocationList.create(
        clock.nowUtc(), ImmutableMap.of(SMD_ID, clock.nowUtc().plusDays(1))).save();
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
  }

  @Test
  public void testSuccess_justBeforeMarkExpirationTime() throws Exception {
    persistContactsAndHosts();
    clock.advanceOneMilli();
    clock.setTo(DateTime.parse("2017-07-23T22:00:00.000Z").minusSeconds(1));
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    setEppInput("domain_create_sunrise_signed_mark_with_secdns.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasExactlyDsData(DelegationSignerData.create(
            12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")));
  }

  @Test
  public void testSuccess_secDnsMaxRecords() throws Exception {
    setEppInput("domain_create_sunrise_signed_mark_with_secdns_8_records.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class)).hasNumDsData(8);
  }

  @Test
  public void testFailure_missingMarks() throws Exception {
    thrown.expect(LandrushApplicationDisallowedDuringSunriseException.class);
    setEppInput("domain_create_sunrise_without_marks.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_sunriseApplicationInLandrush() throws Exception {
    thrown.expect(SunriseApplicationDisallowedDuringLandrushException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_smdRevoked() throws Exception {
    SignedMarkRevocationList.create(clock.nowUtc(), ImmutableMap.of(SMD_ID, clock.nowUtc())).save();
    thrown.expect(SignedMarkRevokedErrorException.class);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_tooManyNameservers() throws Exception {
    thrown.expect(TooManyNameserversException.class);
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_14_nameservers.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_secDnsMaxSigLife() throws Exception {
    thrown.expect(MaxSigLifeNotSupportedException.class);
    setEppInput("domain_create_sunrise_with_secdns_maxsiglife.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_secDnsTooManyDsRecords() throws Exception {
    thrown.expect(TooManyDsRecordsException.class);
    setEppInput("domain_create_sunrise_signed_mark_with_secdns_9_records.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongExtension() throws Exception {
    thrown.expect(UnimplementedExtensionException.class);
    setEppInput("domain_create_sunrise_wrong_extension.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_reserved() throws Exception {
    thrown.expect(DomainReservedException.class);
    setEppInput("domain_create_sunrise_signed_mark_reserved.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_superuserReserved() throws Exception {
    setEppInput("domain_create_sunrise_signed_mark_reserved.xml");
    createTld("tld", TldState.SUNRISE);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrise_signed_mark_reserved_response.xml");
  }

  @Test
  public void testSuccess_premiumNotBlocked() throws Exception {
    createTld("example", TldState.SUNRUSH);
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_landrush_premium.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        readFile("domain_create_landrush_premium_response.xml"),
        "epp.response.extension.creData.applicationID",
        "epp.response.resData.creData.crDate",
        "epp.response.extension.creData.phase");
  }

  @Test
  public void testSuccess_superuserPremiumNameBlock() throws Exception {
    createTld("example", TldState.SUNRUSH);
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_landrush_premium.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    // Modify the Registrar to block premium names.
    persistResource(Registrar.loadByClientId("TheRegistrar").asBuilder()
        .setBlockPremiumNames(true)
        .build());
    runSuperuserFlow("domain_create_landrush_premium_response.xml");
  }

  private DomainApplication persistSunriseApplication() throws Exception {
    return persistResource(newDomainApplication(getUniqueIdFromCommand())
        .asBuilder()
        .setPhase(LaunchPhase.SUNRISE)
        .build());
  }

  @Test
  public void testFailure_landrushWithOneSunriseApplication() throws Exception {
    thrown.expect(UncontestedSunriseApplicationBlockedInLandrushException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush.xml");
    persistSunriseApplication();
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_superuserLandrushWithOneSunriseApplication() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush.xml");
    persistSunriseApplication();
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_landrush_response.xml");
  }

  @Test
  public void testSuccess_landrushWithTwoSunriseApplications() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush.xml");
    persistSunriseApplication();
    persistSunriseApplication();
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false);
  }

  @Test
  public void testSuccess_landrushWithPeriodSpecified() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_two_years.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false, 2);
  }

  @Test
  public void testSuccess_landrushLrpApplication() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    LrpToken token = persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false);
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry()).isNotNull();
  }

  @Test
  public void testSuccess_landrush_duringLrpWithMissingToken() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    setEppInput("domain_create_landrush.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_landrush_response.xml", false);
  }

  @Test
  public void testSuccess_landrushLrpApplication_superuser() throws Exception {
    // Using an LRP token as superuser should still mark the token as redeemed (i.e. same effect
    // as non-superuser).
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    LrpToken token = persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_landrush_response.xml");
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry()).isNotNull();
  }

  @Test
  public void testFailure_landrushLrpApplication_badToken() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    persistResource(new LrpToken.Builder()
        .setToken("lrptokentest2")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(BadAuthInfoForResourceException.class);
    runFlow();
  }

  @Test
  public void testFailure_landrushLrpApplication_tokenForWrongTld() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        // The below assignee doesn't really make sense here, but as of right now the validation
        // in TldSpecificLogicProxy is just a match on the domain name, so this test ensures that
        // the registration fails due to invalid TLDs even if everything else otherwise matches.
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("other"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(BadAuthInfoForResourceException.class);
    runFlow();
  }

  @Test
  public void testFailure_landrushLrpApplication_usedToken() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.LANDRUSH))
        .build());
    persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .setRedemptionHistoryEntry(Key.create(HistoryEntry.class, "1")) // as long as it's not null
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_landrushApplicationWithLrpToken_notInLrp() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    LrpToken token = persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    // Application should continue as normal, since the LRP token will just be ignored
    doSuccessfulTest("domain_create_landrush_response.xml", false);
    // Token should not be marked as used, since this isn't an LRP state
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry()).isNull();
  }

  @Test
  public void testSuccess_landrushApplicationWithLrpToken_differentLrpState() throws Exception {
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder()
        .setLrpTldStates(ImmutableSet.of(TldState.SUNRISE))
        .setTldStateTransitions(ImmutableSortedMap.of(
            START_OF_TIME, TldState.SUNRISE,
            clock.nowUtc(), TldState.LANDRUSH))
        .build());
    LrpToken token = persistResource(new LrpToken.Builder()
        .setToken("lrptokentest")
        .setAssignee("test-validate.tld")
        .setValidTlds(ImmutableSet.of("tld"))
        .build());
    setEppInput("domain_create_landrush_lrp.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    // Application should continue as normal, since the LRP token will just be ignored
    doSuccessfulTest("domain_create_landrush_response.xml", false);
    // Token should not be marked as used, since this isn't an LRP state
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry()).isNull();
  }

  @Test
  public void testFailure_landrushWithPeriodInMonths() throws Exception {
    thrown.expect(BadPeriodUnitException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_months.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_missingHost() throws Exception {
    thrown.expect(LinkedResourcesDoNotExistException.class, "(ns2.example.net)");
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    runFlow();
  }

  @Test
  public void testFailure_missingContact() throws Exception {
    thrown.expect(LinkedResourcesDoNotExistException.class, "(sh8013)");
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    runFlow();
  }

  @Test
  public void testFailure_wrongTld() throws Exception {
    thrown.expect(TldDoesNotExistException.class);
    deleteTld("tld");
    createTld("foo", TldState.SUNRISE);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_predelegation() throws Exception {
    thrown.expect(BadCommandForRegistryPhaseException.class);
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    thrown.expect(NotAuthorizedForTldException.class);
    DatastoreHelper.persistResource(Registrar.loadByClientId("TheRegistrar")
        .asBuilder()
        .setAllowedTlds(ImmutableSet.<String>of())
        .build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_sunrush() throws Exception {
    thrown.expect(LaunchPhaseMismatchException.class);
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_quietPeriod() throws Exception {
    thrown.expect(BadCommandForRegistryPhaseException.class);
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_generalAvailability() throws Exception {
    thrown.expect(BadCommandForRegistryPhaseException.class);
    createTld("tld", TldState.GENERAL_AVAILABILITY);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongDeclaredPhase() throws Exception {
    thrown.expect(LaunchPhaseMismatchException.class);
    setEppInput("domain_create_landrush_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_superuserPredelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrush_response.xml");
  }

  @Test
  public void testSuccess_superuserSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrush_response.xml");
  }

  @Test
  public void testSuccess_superuserQuietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrush_response.xml");
  }

  @Test
  public void testSuccess_superuserGeneralAvailability() throws Exception {
    createTld("tld", TldState.GENERAL_AVAILABILITY);
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrush_response.xml");
  }

  @Test
  public void testSuccess_superuserWrongDeclaredPhase() throws Exception {
    setEppInput("domain_create_landrush_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runSuperuserFlow("domain_create_sunrush_response.xml");
  }

  @Test
  public void testFailure_duplicateContact() throws Exception {
    thrown.expect(DuplicateContactForRoleException.class);
    setEppInput("domain_create_sunrise_duplicate_contact.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_missingContactType() throws Exception {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    thrown.expect(MissingContactTypeException.class);
    setEppInput("domain_create_sunrise_missing_contact_type.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_noMatchingMarks() throws Exception {
    thrown.expect(NoMarksFoundMatchingDomainException.class);
    setEppInput("domain_create_sunrise_no_matching_marks.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_beforeMarkCreationTime() throws Exception {
    // If we move now back in time a bit, the mark will not have gone into effect yet.
    thrown.expect(NoMarksFoundMatchingDomainException.class);
    clock.setTo(DateTime.parse("2013-08-09T10:05:59Z").minusSeconds(1));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_atMarkExpirationTime() throws Exception {
    // Move time forward to the mark expiration time.
    thrown.expect(NoMarksFoundMatchingDomainException.class);
    clock.setTo(DateTime.parse("2017-07-23T22:00:00.000Z"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_hexEncoding() throws Exception {
    thrown.expect(Base64RequiredForEncodedSignedMarksException.class);
    setEppInput("domain_create_sunrise_hex_encoding.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_badEncoding() throws Exception {
    thrown.expect(SignedMarkEncodingErrorException.class);
    setEppInput("domain_create_sunrise_bad_encoding.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_badEncodedXml() throws Exception {
    thrown.expect(SignedMarkParsingErrorException.class);
    setEppInput("domain_create_sunrise_bad_encoded_xml.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_badIdn() throws Exception {
    thrown.expect(InvalidIdnDomainLabelException.class);
    createTld("xn--q9jyb4c", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_bad_idn_minna.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_badValidatorId() throws Exception {
    thrown.expect(InvalidTrademarkValidatorException.class);
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_sunrush_bad_validator_id.xml");
    persistClaimsList(ImmutableMap.of("exampleone", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_signedMark() throws Exception {
    thrown.expect(SignedMarksMustBeEncodedException.class);
    setEppInput("domain_create_sunrise_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_codeMark() throws Exception {
    thrown.expect(UnsupportedMarkTypeException.class);
    setEppInput("domain_create_sunrise_code_with_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_emptyEncodedMarkData() throws Exception {
    thrown.expect(SignedMarkParsingErrorException.class);
    setEppInput("domain_create_sunrise_empty_encoded_signed_mark.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_signedMarkAndNotice() throws Exception {
    thrown.expect(NoticeCannotBeUsedWithSignedMarkException.class);
    setEppInput("domain_create_sunrise_signed_mark_and_notice.xml");
    persistClaimsList(ImmutableMap.of("exampleone", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_twoSignedMarks() throws Exception {
    thrown.expect(TooManySignedMarksException.class);
    setEppInput("domain_create_sunrise_two_signed_marks.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_missingClaimsNotice() throws Exception {
    thrown.expect(MissingClaimsNoticeException.class);
    createTld("tld", TldState.SUNRUSH);
    persistClaimsList(ImmutableMap.of("test-validate", CLAIMS_KEY));
    setEppInput("domain_create_sunrush.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_claimsNoticeProvided_nameNotOnClaimsList() throws Exception {
    thrown.expect(UnexpectedClaimsNoticeException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_claim_notice.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_claimsNoticeProvided_claimsPeriodEnded() throws Exception {
    thrown.expect(ClaimsPeriodEndedException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setClaimsPeriodEnd(clock.nowUtc())
        .build());
    runFlow();
  }

  @Test
  public void testFailure_expiredClaim() throws Exception {
    thrown.expect(ExpiredClaimException.class);
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2010-08-17T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_expiredAcceptance() throws Exception {
    thrown.expect(AcceptedTooLongAgoException.class);
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-09-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_malformedTcnIdWrongLength() throws Exception {
    thrown.expect(MalformedTcnIdException.class);
    createTld("tld", TldState.SUNRUSH);
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_malformed_claim_notice1.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_malformedTcnIdBadChar() throws Exception {
    thrown.expect(MalformedTcnIdException.class);
    createTld("tld", TldState.SUNRUSH);
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_malformed_claim_notice2.xml");
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_badTcnIdChecksum() throws Exception {
    thrown.expect(InvalidTcnIdChecksumException.class);
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_sunrush_bad_checksum_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeLandrushApplication_v06() throws Exception {
    thrown.expect(FeesMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeLandrushApplication_v11() throws Exception {
    thrown.expect(FeesMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeLandrushApplication_v12() throws Exception {
    thrown.expect(FeesMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v06() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v11() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v12() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v06() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v11() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v12() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_landrush_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_alreadyExists() throws Exception {
    // This fails fast and throws DomainAlreadyExistsException from init() as a special case.
    thrown.expect(
        ResourceAlreadyExistsException.class,
        String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    persistActiveDomain(getUniqueIdFromCommand());
    try {
      runFlow();
    } catch (ResourceAlreadyExistsException e) {
      assertThat(e.isFailfast()).isTrue();
      throw e;
    }
  }

  @Test
  public void testFailure_registrantNotWhitelisted() throws Exception {
    persistActiveContact("someone");
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("someone"))
        .build());
    thrown.expect(RegistrantNotAllowedException.class, "jd1234");
    runFlow();
  }

  @Test
  public void testFailure_nameserverNotWhitelisted() throws Exception {
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.net"))
        .build());
    thrown.expect(NameserversNotAllowedException.class, "ns1.example.net");
    runFlow();
  }

  @Test
  public void testSuccess_nameserverAndRegistrantWhitelisted() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("jd1234"))
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.net", "ns2.example.net"))
        .build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest("domain_create_sunrise_encoded_signed_mark_response.xml", true);
    assertAboutApplications().that(getOnlyGlobalResource(DomainApplication.class))
        .hasApplicationStatus(ApplicationStatus.VALIDATED);
  }

  @Test
  public void testFailure_emptyNameserverFailsWhitelist() throws Exception {
    setEppInput("domain_create_sunrise_encoded_signed_mark_no_hosts.xml");
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("jd1234"))
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("somethingelse.example.net"))
        .build());
    persistContactsAndHosts();
    clock.advanceOneMilli();
    thrown.expect(NameserversNotSpecifiedException.class);
    runFlow();
  }

  /**
   * There is special logic that disallows a failfast for domains in add grace period and sunrush
   * add grace period, so make sure that they fail anyways in the actual flow.
   */
  private void doNonFailFastAlreadyExistsTest(GracePeriodStatus gracePeriodStatus)
      throws Exception {
    // This doesn't fail fast, so it throws the regular ResourceAlreadyExistsException from run().
    thrown.expect(
        ResourceAlreadyExistsException.class,
        String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
    persistContactsAndHosts();
    clock.advanceOneMilli();
    persistResource(newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .addGracePeriod(GracePeriod.create(gracePeriodStatus, END_OF_TIME, "", null))
        .build());
    try {
      runFlow();
    } catch (ResourceAlreadyExistsException e) {
      assertThat(e.isFailfast()).isFalse();
      throw e;
    }
  }

  @Test
  public void testFailure_alreadyExists_addGracePeriod() throws Exception {
    doNonFailFastAlreadyExistsTest(GracePeriodStatus.ADD);
  }

  @Test
  public void testFailure_alreadyExists_sunrushAddGracePeriod() throws Exception {
    doNonFailFastAlreadyExistsTest(GracePeriodStatus.SUNRUSH_ADD);
  }

  private void doFailingDomainNameTest(String domainName, Class<? extends Throwable> exception)
      throws Exception {
    thrown.expect(exception);
    setEppInput("domain_create_sunrise_signed_mark_uppercase.xml");
    eppLoader.replaceAll("TEST-VALIDATE.tld", domainName);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_uppercase() throws Exception {
    doFailingDomainNameTest("Example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_badCharacter() throws Exception {
    doFailingDomainNameTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_leadingDash() throws Exception {
    doFailingDomainNameTest("-example.tld", LeadingDashException.class);
  }

  @Test
  public void testFailure_trailingDash() throws Exception {
    doFailingDomainNameTest("example-.tld", TrailingDashException.class);
  }

  @Test
  public void testFailure_tooLong() throws Exception {
    doFailingDomainNameTest(Strings.repeat("a", 64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  public void testFailure_leadingDot() throws Exception {
    doFailingDomainNameTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_leadingDotTld() throws Exception {
    doFailingDomainNameTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_tooManyParts() throws Exception {
    doFailingDomainNameTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_tooFewParts() throws Exception {
    doFailingDomainNameTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_invalidPunycode() throws Exception {
    doFailingDomainNameTest("xn--abcdefg.tld", InvalidPunycodeException.class);
  }

  @Test
  public void testFailure_dashesInThirdAndFourthPosition() throws Exception {
    doFailingDomainNameTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  public void testFailure_tldDoesNotExist() throws Exception {
    doFailingDomainNameTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  public void testFailure_invalidIdnCodePoints() throws Exception {
    // ❤☀☆☂☻♞☯.tld
    doFailingDomainNameTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }
}
