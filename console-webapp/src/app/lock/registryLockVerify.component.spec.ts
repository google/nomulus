// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import { RegistryLockVerifyComponent } from './registryLockVerify.component';

describe('RegistryLockVerifyComponent & RegistryLockVerifyService', () => {
  let backendService: BackendService;
  let registrarIdSignal: ReturnType<typeof signal<string>>;

  beforeEach(async () => {
    registrarIdSignal = signal('');

    await TestBed.configureTestingModule({
      imports: [RegistryLockVerifyComponent, MaterialModule],
      providers: [
        BackendService,
        {
          provide: RegistrarService,
          useValue: { registrarId: registrarIdSignal },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: of(
              convertToParamMap({ lockVerificationCode: 'verify-code-123' })
            ),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    backendService = TestBed.inject(BackendService);
  });

  it('should verify lock request from query param and update domainName, action, and registrarId', () => {
    const verifySpy = vi
      .spyOn(backendService, 'verifyRegistryLockRequest')
      .mockReturnValue(
        of({
          action: 'LOCK',
          domainName: 'locked.tld',
          registrarId: 'reg-verified',
        })
      );

    const fixture = TestBed.createComponent(RegistryLockVerifyComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(verifySpy).toHaveBeenCalledWith('verify-code-123');
    expect(component.isLoading()).toBe(false);
    expect(component.domainName).toBe('locked.tld');
    expect(component.action).toBe('LOCK');
    expect(registrarIdSignal()).toBe('reg-verified');
  });

  it('should display errorMessage when verification fails', () => {
    vi.spyOn(backendService, 'verifyRegistryLockRequest').mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ error: 'Expired verification code' })
      )
    );

    const fixture = TestBed.createComponent(RegistryLockVerifyComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.isLoading()).toBe(false);
    expect(component.errorMessage).toBe('Expired verification code');
  });
});
