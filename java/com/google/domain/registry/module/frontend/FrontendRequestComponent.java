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

package com.google.domain.registry.module.frontend;

import com.google.domain.registry.rdap.RdapAutnumAction;
import com.google.domain.registry.rdap.RdapDomainAction;
import com.google.domain.registry.rdap.RdapDomainSearchAction;
import com.google.domain.registry.rdap.RdapEntityAction;
import com.google.domain.registry.rdap.RdapEntitySearchAction;
import com.google.domain.registry.rdap.RdapHelpAction;
import com.google.domain.registry.rdap.RdapIpAction;
import com.google.domain.registry.rdap.RdapModule;
import com.google.domain.registry.rdap.RdapNameserverAction;
import com.google.domain.registry.rdap.RdapNameserverSearchAction;
import com.google.domain.registry.request.RequestComponent;
import com.google.domain.registry.request.RequestHandler;
import com.google.domain.registry.request.RequestModule;
import com.google.domain.registry.request.RequestScope;
import com.google.domain.registry.ui.server.registrar.ConsoleUiAction;
import com.google.domain.registry.ui.server.registrar.RegistrarPaymentAction;
import com.google.domain.registry.ui.server.registrar.RegistrarPaymentSetupAction;
import com.google.domain.registry.ui.server.registrar.RegistrarUserModule;
import com.google.domain.registry.whois.WhoisHttpServer;
import com.google.domain.registry.whois.WhoisModule;
import com.google.domain.registry.whois.WhoisServer;

import dagger.Subcomponent;

/** Dagger component with per-request lifetime for "default" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
        RdapModule.class,
        RegistrarUserModule.class,
        RequestModule.class,
        WhoisModule.class,
    })
interface FrontendRequestComponent extends RequestComponent<FrontendRequestComponent> {
  ConsoleUiAction consoleUiAction();
  RdapAutnumAction rdapAutnumAction();
  RegistrarPaymentAction registrarPaymentAction();
  RegistrarPaymentSetupAction registrarPaymentSetupAction();
  RdapDomainAction rdapDomainAction();
  RdapDomainSearchAction rdapDomainSearchAction();
  RdapEntityAction rdapEntityAction();
  RdapEntitySearchAction rdapEntitySearchAction();
  RdapHelpAction rdapHelpAction();
  RdapIpAction rdapDefaultAction();
  RdapNameserverAction rdapNameserverAction();
  RdapNameserverSearchAction rdapNameserverSearchAction();
  WhoisHttpServer whoisHttpServer();
  WhoisServer whoisServer();
}
