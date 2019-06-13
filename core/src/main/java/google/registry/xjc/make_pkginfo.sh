#!/bin/bash
# Copyright 2017 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


[[ $# != 2 ]] && { echo "usage: $0 TEMPLATE OUTDIR" >&2; exit 1; }

template="$1"
outdir="$2"

create() {
  package=$1
  namespace=$2
  sed -e "s,@PACKAGE@,${package},g" \
      -e "s,@NAMESPACE@,${namespace},g" \
    < "${template}" \
    > "${outdir}/${package}/package-info.java"
}

create contact urn:ietf:params:xml:ns:contact-1.0
create domain urn:ietf:params:xml:ns:domain-1.0
create dsig http://www.w3.org/2000/09/xmldsig#
create epp urn:ietf:params:xml:ns:epp-1.0
create eppcom urn:ietf:params:xml:ns:eppcom-1.0
create fee06 urn:ietf:params:xml:ns:fee-0.6
create fee11 urn:ietf:params:xml:ns:fee-0.11
create fee12 urn:ietf:params:xml:ns:fee-0.12
create host urn:ietf:params:xml:ns:host-1.0
create iirdea urn:ietf:params:xml:ns:iirdea-1.0
create launch urn:ietf:params:xml:ns:launch-1.0
create mark urn:ietf:params:xml:ns:mark-1.0
create rde urn:ietf:params:xml:ns:rde-1.0
create rdecontact urn:ietf:params:xml:ns:rdeContact-1.0
create rdedomain urn:ietf:params:xml:ns:rdeDomain-1.0
create rdeeppparams urn:ietf:params:xml:ns:rdeEppParams-1.0
create rdeheader urn:ietf:params:xml:ns:rdeHeader-1.0
create rdehost urn:ietf:params:xml:ns:rdeHost-1.0
create rdeidn urn:ietf:params:xml:ns:rdeIDN-1.0
create rdenndn urn:ietf:params:xml:ns:rdeNNDN-1.0
create rdenotification urn:ietf:params:xml:ns:rdeNotification-1.0
create rdepolicy urn:ietf:params:xml:ns:rdePolicy-1.0
create rderegistrar urn:ietf:params:xml:ns:rdeRegistrar-1.0
create rdereport urn:ietf:params:xml:ns:rdeReport-1.0
create rgp urn:ietf:params:xml:ns:rgp-1.0
create secdns urn:ietf:params:xml:ns:secDNS-1.1
create smd urn:ietf:params:xml:ns:signedMark-1.0
