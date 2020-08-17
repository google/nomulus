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

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateServerLocksCommand}. */
class UpdateServerLocksCommandTest extends EppToolCommandTestCase<UpdateServerLocksCommand> {

  @Test
  void testSuccess_applyOne() throws Exception {
    runCommandForced("--client=NewRegistrar", "--registrar_request=true", "--reason=Test",
        "--domain_name=example.tld", "--apply=serverRenewProhibited");
    eppVerifier.verifySent("update_server_locks_apply_one.xml");
  }

  @Test
  void testSuccess_multipleWordReason() throws Exception {
    runCommandForced("--client=NewRegistrar", "--registrar_request=false",
        "--reason=\"Test this\"", "--domain_name=example.tld", "--apply=serverRenewProhibited");
    eppVerifier.verifySent("update_server_locks_multiple_word_reason.xml");
  }

  @Test
  void testSuccess_removeOne() throws Exception {
    runCommandForced("--client=NewRegistrar", "--registrar_request=true", "--reason=Test",
        "--domain_name=example.tld", "--remove=serverRenewProhibited");
    eppVerifier.verifySent("update_server_locks_remove_one.xml");
  }

  @Test
  void testSuccess_applyAll() throws Exception {
    runCommandForced("--client=NewRegistrar", "--registrar_request=true", "--reason=Test",
        "--domain_name=example.tld", "--apply=all");
    eppVerifier.verifySent("update_server_locks_apply_all.xml");
  }

  @Test
  void testSuccess_removeAll() throws Exception {
    runCommandForced("--client=NewRegistrar", "--registrar_request=true", "--reason=Test",
        "--domain_name=example.tld", "--remove=all");
    eppVerifier.verifySent("update_server_locks_remove_all.xml");
  }

  @Test
  void testFailure_applyAllRemoveOne_failsDueToOverlap() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=true",
                "--reason=Test",
                "--domain_name=example.tld",
                "--apply=all",
                "--remove=serverRenewProhibited"));
  }

  @Test
  void testFailure_illegalStatus() {
    // The EPP status is a valid one by RFC, but invalid for this command.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=true",
                "--reason=Test",
                "--domain_name=example.tld",
                "--apply=clientRenewProhibited"));
  }

  @Test
  void testFailure_unrecognizedStatus() {
    // Handles a status passed to the command that doesn't correspond to any
    // EPP-valid status.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=true",
                "--reason=Test",
                "--domain_name=example.tld",
                "--apply=foo"));
  }

  @Test
  void testFailure_mainParameter() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=true",
                "--reason=Test",
                "--domain_name=example.tld",
                "example2.tld",
                "--apply=serverRenewProhibited"));
  }

  @Test
  void testFailure_noOp() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--domain_name=example.tld",
                "--apply=all",
                "--remove=serverRenewProhibited,serverTransferProhibited,"
                    + "serverDeleteProhibited,serverUpdateProhibited,serverHold",
                "--registrar_request=true",
                "--reason=Test"));
  }

  @Test
  void testFailure_missingClientId() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--domain_name=example.tld",
                "--registrar_request=true",
                "--apply=serverRenewProhibited",
                "--reason=Test"));
  }

  @Test
  void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=true",
                "--reason=Test",
                "--domain_name=example.tld",
                "--apply=serverRenewProhibited",
                "--foo=bar"));
  }

  @Test
  void testFailure_noReasonWhenNotRegistrarRequested() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrar_request=false",
                "--domain_name=example.tld",
                "--apply=serverRenewProhibited"));
  }

  @Test
  void testFailure_missingRegistrarRequest() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--reason=Test",
                "--domain_name=example.tld",
                "--apply=serverRenewProhibited"));
  }
}
