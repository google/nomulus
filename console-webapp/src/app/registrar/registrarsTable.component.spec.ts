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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarComponent, columns } from './registrarsTable.component';
import { Registrar, RegistrarService } from './registrar.service';
import { signal } from '@angular/core';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';

describe('RegistrarComponent', () => {
  let component: RegistrarComponent;
  let fixture: ComponentFixture<RegistrarComponent>;
  let router: Router;
  let registrarsSignal: ReturnType<typeof signal<Registrar[]>>;
  let inNewRegistrarModeSignal: ReturnType<typeof signal<boolean>>;

  const sampleRegistrar: Registrar = {
    registrarId: 'reg-acme',
    registrarName: 'Acme Corp',
    allowedTlds: ['app', 'dev'],
    emailAddress: 'admin@acme.example',
    ianaIdentifier: 9999,
    billingAccountMap: { USD: '111', EUR: '222' },
    registryLockAllowed: true,
    driveFolderId: 'folder-xyz',
    icannReferralEmail: 'icann@acme.example',
    localizedAddress: { city: 'NY' },
    url: 'https://acme.example',
  };

  beforeEach(async () => {
    registrarsSignal = signal<Registrar[]>([sampleRegistrar]);
    inNewRegistrarModeSignal = signal(false);

    await TestBed.configureTestingModule({
      imports: [RegistrarComponent, MaterialModule],
      providers: [
        BackendService,
        {
          provide: RegistrarService,
          useValue: {
            registrars: registrarsSignal,
            inNewRegistrarMode: inNewRegistrarModeSignal,
          },
        },
        { provide: ActivatedRoute, useValue: {} as ActivatedRoute },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(RegistrarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should populate table dataSource when registrars signal updates', () => {
    expect(component.dataSource.data).toEqual([sampleRegistrar]);

    const secondRegistrar: Registrar = {
      ...sampleRegistrar,
      registrarId: 'reg-beta',
      registrarName: 'Beta LLC',
    };
    registrarsSignal.set([sampleRegistrar, secondRegistrar]);
    TestBed.flushEffects();

    expect(component.dataSource.data).toHaveLength(2);
  });

  it('should format table columns accurately', () => {
    const byDef = Object.fromEntries(columns.map((c) => [c.columnDef, c]));
    expect(byDef['registrarId'].cell(sampleRegistrar)).toBe('reg-acme');
    expect(byDef['registrarName'].cell(sampleRegistrar)).toBe('Acme Corp');
    expect(byDef['allowedTlds'].cell(sampleRegistrar)).toBe('app, dev');
    expect(byDef['emailAddress'].cell(sampleRegistrar)).toBe(
      'admin@acme.example'
    );
    expect(byDef['ianaIdentifier'].cell(sampleRegistrar)).toBe('9999');
    expect(byDef['billingAccountMap'].cell(sampleRegistrar)).toBe(
      'USD=111\nEUR=222\n'
    );
    expect(byDef['registryLockAllowed'].cell(sampleRegistrar)).toBe('true');
    expect(byDef['driveId'].cell(sampleRegistrar)).toBe('folder-xyz');
  });

  it('should normalize and apply filter from input event', () => {
    const event = {
      target: { value: '  ACME Corp  ' },
    } as unknown as Event;
    component.applyFilter(event);
    expect(component.dataSource.filter).toBe('acme corp');
  });

  it('should navigate to registrar details and new OTE account', () => {
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    component.openDetails('reg-acme');
    expect(navigateSpy).toHaveBeenCalledWith(['registrars/', 'reg-acme'], {
      queryParamsHandling: 'merge',
    });

    component.createOteAccount();
    expect(navigateSpy).toHaveBeenCalledWith(['new-ote']);
  });

  it('should enable new registrar mode, rebind paginator and sort on return, and return OTE restricted element ID', () => {
    expect(component.getElementIdForOteBlock()).toBe(RESTRICTED_ELEMENTS.OTE);
    expect(component.dataSource.paginator).toBeTruthy();
    expect(component.dataSource.sort).toBeTruthy();

    component.openNewRegistrar();
    expect(inNewRegistrarModeSignal()).toBe(true);
    fixture.detectChanges();
    expect(component.dataSource.paginator).toBeNull();
    expect(component.dataSource.sort).toBeNull();

    inNewRegistrarModeSignal.set(false);
    fixture.detectChanges();
    expect(component.dataSource.paginator).toBeTruthy();
    expect(component.dataSource.sort).toBeTruthy();
  });
});
