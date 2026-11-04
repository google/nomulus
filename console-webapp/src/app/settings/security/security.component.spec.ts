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
import { FormsModule } from '@angular/forms';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { MaterialModule } from 'src/app/material.module';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import { SecurityComponent } from './security.component';
import { SecurityService } from './security.service';
import { SecurityEditComponent } from './securityEdit.component';
import { EppPasswordEditComponent } from './eppPasswordEdit.component';
import { MOCK_REGISTRAR_SERVICE } from 'src/testdata/registrar/registrar.service.mock';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal, WritableSignal } from '@angular/core';
import { UserDataService } from 'src/app/shared/services/userData.service';
import { Registrar } from 'src/app/registrar/registrar.service';

describe('SecurityComponent & EppPasswordEditComponent', () => {
  let component: SecurityComponent;
  let fixture: ComponentFixture<SecurityComponent>;
  let saveSpy: ReturnType<typeof vi.fn>;
  let saveEppPasswordSpy: ReturnType<typeof vi.fn>;
  let securityServiceStub: SecurityService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        SecurityEditComponent,
        EppPasswordEditComponent,
        SecurityComponent,
        MaterialModule,
        BrowserAnimationsModule,
        FormsModule,
      ],
      providers: [
        BackendService,
        SecurityService,
        {
          provide: RegistrarService,
          useValue: {
            ...MOCK_REGISTRAR_SERVICE,
            registrarId: signal('reg-1'),
          },
        },
        {
          provide: UserDataService,
          useValue: { userData: signal({ isAdmin: true }) },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    securityServiceStub = TestBed.inject(SecurityService);
    saveSpy = vi
      .spyOn(securityServiceStub, 'saveChanges')
      .mockReturnValue(of([]));
    saveEppPasswordSpy = vi
      .spyOn(securityServiceStub, 'saveEppPassword')
      .mockReturnValue(of([]));

    fixture = TestBed.createComponent(SecurityComponent);
    component = fixture.componentInstance;
  });

  it('should render security elements', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    const listElems: Array<HTMLElement> = Array.from(
      fixture.nativeElement.querySelectorAll('span.console-app__list-value')
    );
    expect(listElems).toHaveLength(8);
    expect(listElems.map((e) => e.textContent)).toEqual([
      'Change the password used for EPP logins',
      '••••••••••••••',
      'Restrict access to EPP production servers to the following IP/IPv6 addresses, or ranges like 1.1.1.0/24',
      '123.123.123.123',
      'X.509 PEM certificate for EPP production access',
      'No client certificate on file.',
      'X.509 PEM backup certificate for EPP production access',
      'No failover certificate on file.',
    ]);
  });

  it('should re-render IP allowlist when registrar signal updates asynchronously', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    registrarSignal.set(undefined);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain(
      'No IP addresses on file.'
    );

    registrarSignal.set({
      ...originalRegistrar!,
      ipAddressAllowList: ['1.1.1.0/24', '1.9.9.0/24'],
    });
    fixture.detectChanges();
    await fixture.whenStable();

    const listValues: Array<string | null> = Array.from(
      fixture.nativeElement.querySelectorAll('span.console-app__list-value'),
      (e: HTMLElement) => e.textContent
    );
    expect(listValues).toContain('1.1.1.0/24');
    expect(listValues).toContain('1.9.9.0/24');
    registrarSignal.set(originalRegistrar);
  });

  it('should remove ip', async () => {
    fixture.detectChanges();
    await fixture.whenStable();

    const editBtn = fixture.nativeElement.querySelector(
      'button[aria-label="Edit security settings"]'
    );
    editBtn.click();

    fixture.detectChanges();
    await fixture.whenStable();

    const removeIpBtn = fixture.nativeElement.querySelector(
      '.console-app__removeIp'
    );
    removeIpBtn.click();

    fixture.detectChanges();
    await fixture.whenStable();

    const saveBtn = fixture.nativeElement.querySelector(
      '.settings-security__edit-save'
    );
    saveBtn.click();

    fixture.detectChanges();
    await fixture.whenStable();

    expect(saveSpy).toHaveBeenCalledWith({
      ipAddressAllowList: [],
    });
  });

  it('should toggle isEditingSecurity and isEditingPassword', () => {
    expect(component.securityService.isEditingSecurity()).toBe(false);
    component.editSecurity();
    expect(component.securityService.isEditingSecurity()).toBe(true);

    expect(component.securityService.isEditingPassword()).toBe(false);
    component.editEppPassword();
    expect(component.securityService.isEditingPassword()).toBe(true);
  });

  it('should call save with updated clientCertificate', async () => {
    fixture.detectChanges();
    await fixture.whenStable();

    const editBtn = fixture.nativeElement.querySelector(
      'button[aria-label="Edit security settings"]'
    );
    editBtn.click();

    fixture.detectChanges();
    await fixture.whenStable();

    const el = fixture.nativeElement.querySelector(
      '.console-app__clientCertificateValue'
    );
    el.value = 'test';
    el.dispatchEvent(new Event('input'));

    fixture.detectChanges();
    await fixture.whenStable();

    const saveBtn = fixture.nativeElement.querySelector(
      '.settings-security__edit-save'
    );
    saveBtn.click();

    await fixture.whenStable();
    expect(saveSpy).toHaveBeenCalledWith({
      ipAddressAllowList: [{ value: '123.123.123.123' }],
      clientCertificate: 'test',
    });
  });

  it('should save EPP password and request EPP password reset in EppPasswordEditComponent', () => {
    const backendService = TestBed.inject(BackendService);
    const resetSpy = vi
      .spyOn(backendService, 'requestEppPasswordReset')
      .mockReturnValue(of({}));

    const eppFixture = TestBed.createComponent(EppPasswordEditComponent);
    const snackBarSpy = vi
      .spyOn(eppFixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    const dialogSpy = vi
      .spyOn(eppFixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(true) } as any);
    const eppComponent = eppFixture.componentInstance;
    eppFixture.detectChanges();

    securityServiceStub.isEditingPassword.set(true);
    eppComponent.save({
      oldPassword: 'oldPass123',
      newPassword: 'newPass123',
      newPasswordRepeat: 'newPass123',
    });

    expect(saveEppPasswordSpy).toHaveBeenCalledWith({
      registrarId: 'reg-1',
      oldPassword: 'oldPass123',
      newPassword: 'newPass123',
      newPasswordRepeat: 'newPass123',
    });
    expect(securityServiceStub.isEditingPassword()).toBe(false);

    securityServiceStub.isEditingPassword.set(true);
    eppComponent.requestEppPasswordReset();
    expect(dialogSpy).toHaveBeenCalled();
    expect(resetSpy).toHaveBeenCalledWith('reg-1');
    expect(securityServiceStub.isEditingPassword()).toBe(false);

    saveEppPasswordSpy.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ error: 'Wrong old password' }))
    );
    eppComponent.save({
      oldPassword: 'wrong',
      newPassword: 'newPass123',
      newPasswordRepeat: 'newPass123',
    });
    expect(snackBarSpy).toHaveBeenCalledWith('Wrong old password');
  });
});
