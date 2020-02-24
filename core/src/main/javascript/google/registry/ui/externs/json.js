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

/**
 * @fileoverview External JSON definitions. The purpose of this file is to give
 *     type information to the JavaScript compiler so it won't rename these
 *     properties.
 * @externs
 */

/**
 * @suppress {duplicate}
 */
var registry = {};


/**
 * @suppress {duplicate}
 */
registry.json = {};

registry.json.locks = {};

/**
 * @typedef {{
 *   fullyQualifiedDomainName: string,
 *   lockedTime: string,
 *   lockedBy: string,
 *   userCanUnlock: boolean,
 *   isPending: boolean
 * }}
 */
registry.json.locks.ExistingLock;

/**
 * @typedef {{
 *   clientId: string,
 *   email: string,
 *   details: !Array.<registry.json.locks.ExistingLock>,
 *   lockEnabledForContact: boolean
 * }}
 */
registry.json.locks.ExistingLocksResult;

/**
 * @typedef {{
 *   status: string,
 *   message: string,
 *   results: !Array.<registry.json.locks.ExistingLocksResult>
 * }}
 */
registry.json.locks.ExistingLocksResponse;

registry.json.ote = {};

/**
 * @typedef {{
 *   description: string,
 *   requirement: number,
 *   timesPerformed: number,
 *   completed: boolean
 * }}
 */
registry.json.ote.OteStatusDetail;


/**
 * @typedef {{
 *   clientId: string,
 *   completed: boolean,
 *   details: !Array.<registry.json.ote.OteStatusDetail>
 * }}
 */
registry.json.ote.OteStatusResult;


/**
 * @typedef {{
 *   status: string,
 *   message: string,
 *   results: !Array.<registry.json.ote.OteStatusResult>
 * }}
 */
registry.json.ote.OteStatusResponse;


/**
 * @constructor
 * @template T
 */
registry.json.Response = function() {};


/**
 * Request state which can be `SUCCESS` or `ERROR`.
 * @type {string}
 */
registry.json.Response.prototype.status;


/**
 * @type {string}
 */
registry.json.Response.prototype.message;


/**
 * @type {string|undefined}
 */
registry.json.Response.prototype.field;


/**
 * @type {!Array.<T>}
 */
registry.json.Response.prototype.results;


// XXX: Might not need undefineds here.
/**
 * @typedef {{
 *   allowedTlds: !Array<string>,
 *   clientIdentifier: string,
 *   clientCertificate: string?,
 *   clientCertificateHash: string?,
 *   failoverClientCertificate: string?,
 *   failoverClientCertificateHash: string?,
 *   driveFolderId: string?,
 *   ianaIdentifier: (number?|undefined),
 *   icannReferralEmail: string,
 *   ipAddressWhitelist: !Array<string>,
 *   emailAddress: (string?|undefined),
 *   lastUpdateTime: string,
 *   url: (string?|undefined),
 *   phonePasscode: (string?|undefined),
 *   phoneNumber: (string?|undefined),
 *   faxNumber: (string?|undefined),
 *   localizedAddress: registry.json.RegistrarAddress,
 *   whoisServer: (string?|undefined),
 *   referralUrl: (string?|undefined),
 *   contacts: !Array.<registry.json.RegistrarContact>,
 *   registryLockAllowed: boolean
 * }}
 */
registry.json.Registrar;


/**
 * @typedef {{
 *   street: !Array.<string>,
 *   city: string,
 *   state: (string?|undefined),
 *   zip: (string?|undefined),
 *   countryCode: string
 * }}
 */
registry.json.RegistrarAddress;


/**
 * @typedef {{
 *   name: (string?|undefined),
 *   emailAddress: string,
 *   visibleInWhoisAsAdmin: boolean,
 *   visibleInWhoisAsTech: boolean,
 *   visibleInDomainWhoisAsAbuse: boolean,
 *   phoneNumber: (string?|undefined),
 *   faxNumber: (string?|undefined),
 *   types: (string?|undefined),
 *   allowedToSetRegistryLockPassword: boolean,
 *   registryLockAllowed: boolean
 * }}
 */
registry.json.RegistrarContact;
