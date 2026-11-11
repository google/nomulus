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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { MatChipInputEvent } from '@angular/material/chips';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { Registrar, RegistrarService } from './registrar.service';
import { RegistrarDetailsComponent } from './registrarDetails.component';
import { NewRegistrarComponent } from './newRegistrar.component';

describe('RegistrarDetailsComponent & NewRegistrarComponent', () => {
  let paramMapSubject: BehaviorSubject<any>;
  let registrarsSignal: ReturnType<typeof signal<Registrar[]>>;
  let inNewRegistrarModeSignal: ReturnType<typeof signal<boolean>>;
  let mockRegistrarService: any;
  let router: Router;

  const sampleRegistrar: Registrar = {
    registrarId: 'reg-1',
    registrarName: 'Registrar One',
    allowedTlds: ['app'],
    icannReferralEmail: 'icann@one.example',
    localizedAddress: { city: 'NYC' },
    url: 'https://one.example',
  };

  beforeEach(async () => {
    paramMapSubject = new BehaviorSubject(convertToParamMap({ id: 'reg-1' }));
    registrarsSignal = signal<Registrar[]>([sampleRegistrar]);
    inNewRegistrarModeSignal = signal(true);

    mockRegistrarService = {
      registrars: registrarsSignal,
      registrarsLoaded: Promise.resolve(),
      inNewRegistrarMode: inNewRegistrarModeSignal,
      updateRegistrar: vi.fn().mockReturnValue(of(sampleRegistrar)),
      createRegistrar: vi.fn().mockReturnValue(of([sampleRegistrar])),
    };

    await TestBed.configureTestingModule({
      imports: [
        RegistrarDetailsComponent,
        NewRegistrarComponent,
        MaterialModule,
      ],
      providers: [
        BackendService,
        { provide: RegistrarService, useValue: mockRegistrarService },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: paramMapSubject.asObservable() },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
  });

  describe('RegistrarDetailsComponent', () => {
    let fixture: ComponentFixture<RegistrarDetailsComponent>;
    let component: RegistrarDetailsComponent;
    let snackBarOpenSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(async () => {
      fixture = TestBed.createComponent(RegistrarDetailsComponent);
      component = fixture.componentInstance;
      snackBarOpenSpy = vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      fixture.detectChanges();
      await fixture.whenStable();
    });

    it('should load registrar from route param and handle missing registrar ID', async () => {
      expect(component.registrarNotFound()).toBe(false);
      expect(component.registrarInEdit.registrarId).toBe('reg-1');

      paramMapSubject.next(convertToParamMap({ id: 'missing-id' }));
      expect(component.registrarNotFound()).toBe(true);
      expect(snackBarOpenSpy).toHaveBeenCalledWith(
        'Registrar with id missing-id is not available'
      );
    });

    it('should add lowercased deduplicated TLDs and remove TLDs', () => {
      component.addTLD({ value: 'DEV' } as MatChipInputEvent);
      expect(component.registrarInEdit.allowedTlds).toEqual(['app', 'dev']);

      component.addTLD({ value: 'app' } as MatChipInputEvent);
      expect(component.registrarInEdit.allowedTlds).toEqual(['dev', 'app']);

      component.removeTLD('dev');
      expect(component.registrarInEdit.allowedTlds).toEqual(['app']);
    });

    it('should navigate to OTE status and save updated registrar', () => {
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      component.checkOteStatus();
      expect(navigateSpy).toHaveBeenCalledWith(['ote-status', 'reg-1'], {
        queryParamsHandling: 'merge',
      });

      component.inEdit = true;
      component.saveAndClose();
      expect(mockRegistrarService.updateRegistrar).toHaveBeenCalledWith(
        component.registrarInEdit
      );
      expect(component.inEdit).toBe(false);
      expect(navigateSpy).toHaveBeenCalledWith(['registrars'], {
        queryParamsHandling: 'merge',
      });

      mockRegistrarService.updateRegistrar.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'Update failed' }))
      );
      component.saveAndClose();
      expect(snackBarOpenSpy).toHaveBeenCalledWith('Update failed');
    });
  });

  describe('NewRegistrarComponent', () => {
    let fixture: ComponentFixture<NewRegistrarComponent>;
    let component: NewRegistrarComponent;
    let snackBarOpenSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      fixture = TestBed.createComponent(NewRegistrarComponent);
      component = fixture.componentInstance;
      snackBarOpenSpy = vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      fixture.detectChanges();
    });

    it('should parse multiline currency=billingCode entries into billingAccountMap', () => {
      component.onBillingAccountMapChange(
        ' USD = acc-1 \n EUR = acc-2 \ninvalid'
      );
      expect((component as any).newRegistrar.billingAccountMap).toEqual({
        USD: 'acc-1',
        EUR: 'acc-2',
      });
    });

    it('should validate form, populate street lines, and create registrar on save', () => {
      (component as any).localizedAddressStreet = {
        line1: '1600 Amphitheatre Pkwy',
        line2: '',
        line3: 'Bldg 43',
      };
      vi.spyOn(component.form().nativeElement, 'checkValidity').mockReturnValue(
        true
      );
      vi.spyOn(component.form().nativeElement, 'reportValidity');

      const submitEvent = { preventDefault: vi.fn() } as unknown as SubmitEvent;
      component.save(submitEvent);

      expect(submitEvent.preventDefault).toHaveBeenCalled();
      expect((component as any).newRegistrar.localizedAddress.street).toEqual([
        '1600 Amphitheatre Pkwy',
        'Bldg 43',
      ]);
      expect(mockRegistrarService.createRegistrar).toHaveBeenCalled();
      expect(inNewRegistrarModeSignal()).toBe(false);
    });

    it('should call reportValidity when form is invalid and show snackbar on backend error', () => {
      const checkValiditySpy = vi
        .spyOn(component.form().nativeElement, 'checkValidity')
        .mockReturnValue(false);
      const reportValiditySpy = vi
        .spyOn(component.form().nativeElement, 'reportValidity')
        .mockImplementation(() => false);

      const submitEvent = { preventDefault: vi.fn() } as unknown as SubmitEvent;
      component.save(submitEvent);
      expect(reportValiditySpy).toHaveBeenCalled();

      checkValiditySpy.mockReturnValue(true);
      mockRegistrarService.createRegistrar.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'Duplicate ID' }))
      );
      component.save(submitEvent);
      expect(snackBarOpenSpy).toHaveBeenCalledWith('Duplicate ID');
    });
  });
});
