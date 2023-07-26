// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import { TestBed } from '@angular/core/testing';

import {
  SecurityService,
  SecuritySettings,
  SecuritySettingsBackendModel,
  apiToUiConverter,
  uiToApiConverter,
} from './security.service';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import SecurityComponent from './security.component';
import { BackendService } from 'src/app/shared/services/backend.service';

describe('SecurityService', () => {
  const uiMockData: SecuritySettings = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: [{ value: '123.123.123.123' }],
  };
  const apiMockData: SecuritySettingsBackendModel = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: ['123.123.123.123'],
  };

  let service: SecurityService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      declarations: [SecurityComponent],
      providers: [SecurityService, BackendService],
    });
    service = TestBed.inject(SecurityService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should convert from api to ui', () => {
    expect(apiToUiConverter(apiMockData)).toEqual(uiMockData);
  });

  it('should convert from ui to api', () => {
    expect(uiToApiConverter(uiMockData)).toEqual(apiMockData);
  });
});
