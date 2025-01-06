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

/**
 * @fileoverview Entry point for the registrar console.
 */

goog.provide('registry.registrar.main');

goog.require('registry.registrar.Console');


/**
 * Instantiates a registry object, which syncs with the server.
 * Sub-templates are invoked based on location/pathname, to choose
 * either Registry or Registrar pages.
 *
 * @param {string} xsrfToken populated by server-side soy template.
 * @param {string} clientId The registrar clientId.
 * @param {boolean} isAdmin
 * @param {boolean} isOwner
 * @param {string} productName the product name displayed by the UI.
 * @param {string} integrationEmail
 * @param {string} supportEmail
 * @param {string} announcementsEmail
 * @param {string} supportPhoneNumber
 * @param {string} technicalDocsUrl
 * @param {string} environment
 * @export
 */
registry.registrar.main = function(
    xsrfToken, clientId, isAdmin, isOwner, productName, integrationEmail,
    supportEmail, announcementsEmail, supportPhoneNumber, technicalDocsUrl,
    environment) {
  const console = new registry.registrar.Console({
    xsrfToken: xsrfToken,
    clientId: clientId,
    isAdmin: isAdmin,
    isOwner: isOwner,
    productName: productName,
    integrationEmail: integrationEmail,
    supportEmail: supportEmail,
    announcementsEmail: announcementsEmail,
    supportPhoneNumber: supportPhoneNumber,
    technicalDocsUrl: technicalDocsUrl,
    environment: environment,
  });

  console.setUp();
};
