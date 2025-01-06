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

import { Component, computed } from '@angular/core';
import { RegistrarService } from 'src/app/registrar/registrar.service';

import { WhoisService } from './whois.service';

@Component({
  selector: 'app-whois',
  templateUrl: './whois.component.html',
  styleUrls: ['./whois.component.scss'],
})
export default class WhoisComponent {
  public static PATH = 'whois';
  formattedAddress = computed(() => {
    let result = '';
    const registrar = this.registrarService.registrar();
    if (registrar?.localizedAddress?.street) {
      result += `${registrar?.localizedAddress?.street?.join(' ')} `;
    }
    if (registrar?.localizedAddress?.city) {
      result += `${registrar?.localizedAddress?.city} `;
    }
    if (registrar?.localizedAddress?.state) {
      result += `${registrar?.localizedAddress?.state} `;
    }
    if (registrar?.localizedAddress?.countryCode) {
      result += `${registrar?.localizedAddress?.countryCode} `;
    }
    if (registrar?.localizedAddress?.zip) {
      result += registrar?.localizedAddress?.zip;
    }
    return result;
  });

  constructor(
    public whoisService: WhoisService,
    public registrarService: RegistrarService
  ) {}
}
