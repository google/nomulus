// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { MaterialModule } from 'src/app/material.module';
import {
  Registrar,
  RegistrarService,
} from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import { RdapComponent } from './rdap.component';
import { RdapEditComponent } from './rdapEdit.component';
import { RdapService } from './rdap.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';

describe('RdapComponent & RdapEditComponent & RdapService', () => {
  let component: RdapComponent;
  let fixture: ComponentFixture<RdapComponent>;
  let rdapService: RdapService;
  let backendService: BackendService;
  let registrarSignal: ReturnType<
    typeof signal<Partial<Registrar> | undefined>
  >;
  let loadRegistrarsSpy: ReturnType<typeof vi.fn>;

  const sampleRegistrar: Registrar = {
    registrarId: 'reg-1',
    registrarName: 'Example Registrar',
    icannReferralEmail: 'referral@example.com',
    url: 'https://example.com',
    localizedAddress: {
      street: ['100 Main St', 'Suite 200'],
      city: 'New York',
      state: 'NY',
      countryCode: 'US',
      zip: '10001',
    },
  };

  beforeEach(async () => {
    registrarSignal = signal<Partial<Registrar> | undefined>(sampleRegistrar);
    loadRegistrarsSpy = vi.fn().mockReturnValue(of([sampleRegistrar]));

    await TestBed.configureTestingModule({
      imports: [
        RdapComponent,
        RdapEditComponent,
        MaterialModule,
        BrowserAnimationsModule,
      ],
      providers: [
        BackendService,
        RdapService,
        {
          provide: RegistrarService,
          useValue: {
            registrar: registrarSignal,
            registrarId: signal('reg-1'),
            registrars: signal([sampleRegistrar]),
            loadRegistrars: loadRegistrarsSpy,
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    rdapService = TestBed.inject(RdapService);
    backendService = TestBed.inject(BackendService);
    fixture = TestBed.createComponent(RdapComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should format full and partial localized addresses in formattedAddress signal', () => {
    expect(component.formattedAddress()).toBe(
      '100 Main St Suite 200 New York NY US 10001'
    );

    registrarSignal.set({
      localizedAddress: {
        city: 'London',
        countryCode: 'GB',
      },
    });
    expect(component.formattedAddress()).toBe('London GB ');

    registrarSignal.set(undefined);
    expect(component.formattedAddress()).toBe('');
  });

  it('should post RDAP registrar fields and reload registrars via RdapService', () => {
    const postSpy = vi
      .spyOn(backendService, 'postRdapRegistrarFields')
      .mockReturnValue(of(sampleRegistrar));

    rdapService.saveChanges(sampleRegistrar).subscribe();

    expect(postSpy).toHaveBeenCalledWith(sampleRegistrar);
    expect(loadRegistrarsSpy).toHaveBeenCalled();
  });

  it('should clone active registrar in RdapEditComponent and save or handle errors', () => {
    const editFixture = TestBed.createComponent(RdapEditComponent);
    const snackBarSpy = vi
      .spyOn(editFixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    const editComponent = editFixture.componentInstance;
    editFixture.detectChanges();

    expect(editComponent.registrarInEdit).toEqual(sampleRegistrar);
    expect(editComponent.registrarInEdit).not.toBe(sampleRegistrar);

    rdapService.editing.set(true);
    const saveSpy = vi
      .spyOn(rdapService, 'saveChanges')
      .mockReturnValueOnce(of([sampleRegistrar]))
      .mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'Invalid RDAP URL' }))
      );

    const submitEvent = { preventDefault: vi.fn() } as unknown as SubmitEvent;
    editComponent.save(submitEvent);
    expect(saveSpy).toHaveBeenCalledWith(sampleRegistrar);
    expect(rdapService.editing()).toBe(false);

    editComponent.save(submitEvent);
    expect(snackBarSpy).toHaveBeenCalledWith('Invalid RDAP URL');
  });
});
