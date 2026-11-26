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
import { By } from '@angular/platform-browser';

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { FormsModule } from '@angular/forms';
import { of, Subject, throwError } from 'rxjs';
import { MaterialModule } from 'src/app/material.module';
import {
  Registrar,
  RegistrarService,
} from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import { GlobalLoaderService } from 'src/app/shared/services/globalLoader.service';
import { SecurityComponent } from './security.component';
import { SecurityService } from './security.service';
import { SecurityEditComponent } from './securityEdit.component';
import { EppPasswordEditComponent } from './eppPasswordEdit.component';
import { MOCK_REGISTRAR_SERVICE } from 'src/testdata/registrar/registrar.service.mock';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal, WritableSignal } from '@angular/core';
import {
  UserData,
  UserDataService,
} from 'src/app/shared/services/userData.service';

const DEFAULT_USER_DATA: UserData = {
  allowEppClientCertHostnameAuth: false,
  globalRole: 'FTE',
  isAdmin: true,
  productName: 'Nomulus',
  supportEmail: 'support@example.com',
  supportPhoneNumber: '+1 (212) 867 5309',
  technicalDocsUrl: 'test',
};

describe('SecurityComponent & EppPasswordEditComponent', () => {
  let component: SecurityComponent;
  let fixture: ComponentFixture<SecurityComponent>;
  let saveSpy: ReturnType<typeof vi.fn>;
  let saveEppPasswordSpy: ReturnType<typeof vi.fn>;
  let securityServiceStub: SecurityService;
  let userDataSignal: WritableSignal<UserData | undefined>;

  beforeEach(async () => {
    userDataSignal = signal<UserData | undefined>({ ...DEFAULT_USER_DATA });
    await TestBed.configureTestingModule({
      imports: [
        SecurityEditComponent,
        EppPasswordEditComponent,
        SecurityComponent,
        MaterialModule,
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
          useValue: { userData: userDataSignal },
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

  it('should omit hidden hostname and revoked hash fields when flag is inactive', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    try {
      registrarSignal.set({
        ...originalRegistrar!,
        allowedEppClientCertificateHostnames: ['epp.stored.example'],
        revokedClientCertificateHashes: ['storedHash123'],
      });
      fixture.detectChanges();
      await fixture.whenStable();

      expect(fixture.nativeElement.textContent).not.toContain(
        'Allowed EPP Client Certificate Hostnames'
      );
      expect(fixture.nativeElement.textContent).not.toContain(
        'Revoked Client Certificate Hashes'
      );

      fixture.nativeElement
        .querySelector('button[aria-label="Edit security settings"]')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(
        fixture.nativeElement.querySelector(
          '.console-app__allowedHostnameValue'
        )
      ).toBeNull();
      expect(
        fixture.nativeElement.querySelector('.console-app__revokedHashValue')
      ).toBeNull();

      fixture.nativeElement
        .querySelector('.settings-security__edit-save')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(saveSpy).toHaveBeenCalledWith({
        ipAddressAllowList: [{ value: '123.123.123.123' }],
      });
      const savedPayload = saveSpy.mock.calls[0][0];
      expect('allowedEppClientCertificateHostnames' in savedPayload).toBe(
        false
      );
      expect('revokedClientCertificateHashes' in savedPayload).toBe(false);
    } finally {
      registrarSignal.set(originalRegistrar);
    }
  });

  it('should render hostnames and revoked certificate hashes when flag is active', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    try {
      userDataSignal.set({
        ...DEFAULT_USER_DATA,
        allowEppClientCertHostnameAuth: true,
      });
      fixture.detectChanges();
      await fixture.whenStable();

      expect(fixture.nativeElement.textContent).toContain(
        'Allowed EPP Client Certificate Hostnames'
      );
      expect(fixture.nativeElement.textContent).toContain(
        'No allowed certificate hostnames on file.'
      );
      expect(fixture.nativeElement.textContent).toContain(
        'Revoked Client Certificate Hashes'
      );
      expect(fixture.nativeElement.textContent).toContain(
        'No revoked client certificate hashes on file.'
      );

      registrarSignal.set({
        ...originalRegistrar!,
        allowedEppClientCertificateHostnames: [
          'epp1.registrar.example',
          'epp2.registrar.example',
        ],
        revokedClientCertificateHashes: ['revokedHash1'],
      });
      fixture.detectChanges();
      await fixture.whenStable();

      const listValues: Array<string | null> = Array.from(
        fixture.nativeElement.querySelectorAll('span.console-app__list-value'),
        (e: HTMLElement) => e.textContent
      );
      expect(listValues).toContain('epp1.registrar.example');
      expect(listValues).toContain('epp2.registrar.example');
      expect(listValues).toContain('revokedHash1');
      expect(fixture.nativeElement.textContent).not.toContain(
        'No allowed certificate hostnames on file.'
      );
      expect(fixture.nativeElement.textContent).not.toContain(
        'No revoked client certificate hashes on file.'
      );
    } finally {
      registrarSignal.set(originalRegistrar);
    }
  });

  it('should add, remove, and save hostnames and revoked certificate hashes when flag is active', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    try {
      userDataSignal.set({
        ...DEFAULT_USER_DATA,
        allowEppClientCertHostnameAuth: true,
      });
      registrarSignal.set({
        ...originalRegistrar!,
        allowedEppClientCertificateHostnames: [
          'dup.registrar.example',
          'dup.registrar.example',
        ],
        revokedClientCertificateHashes: ['dupHash', 'dupHash'],
      });
      fixture.detectChanges();
      await fixture.whenStable();

      fixture.nativeElement
        .querySelector('button[aria-label="Edit security settings"]')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      // Remove only the first duplicate hostname row and add a new one.
      fixture.nativeElement
        .querySelector('.console-app__removeHostname')
        .click();
      fixture.nativeElement
        .querySelector(
          'button[aria-label="Add new allowed certificate hostname"]'
        )
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      const hostnameInputs = fixture.nativeElement.querySelectorAll(
        '.console-app__allowedHostnameValue'
      );
      expect(hostnameInputs).toHaveLength(2);
      hostnameInputs[1].value = 'epp.new.example';
      hostnameInputs[1].dispatchEvent(new Event('input'));

      // Remove only the first duplicate revoked hash row and add a new one.
      fixture.nativeElement
        .querySelector('.console-app__removeRevokedHash')
        .click();
      fixture.nativeElement
        .querySelector('button[aria-label="Add new revoked certificate hash"]')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      const hashInputs = fixture.nativeElement.querySelectorAll(
        '.console-app__revokedHashValue'
      );
      expect(hashInputs).toHaveLength(2);
      hashInputs[1].value = 'newRevokedHash';
      hashInputs[1].dispatchEvent(new Event('input'));
      fixture.detectChanges();
      await fixture.whenStable();

      fixture.nativeElement
        .querySelector('.settings-security__edit-save')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(saveSpy).toHaveBeenCalledWith({
        ipAddressAllowList: [{ value: '123.123.123.123' }],
        allowedEppClientCertificateHostnames: [
          { value: 'dup.registrar.example' },
          { value: 'epp.new.example' },
        ],
        revokedClientCertificateHashes: [
          { value: 'dupHash' },
          { value: 'newRevokedHash' },
        ],
      });
    } finally {
      registrarSignal.set(originalRegistrar);
    }
  });

  it('should fail closed and omit hidden fields when userData is not loaded yet', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    try {
      userDataSignal.set(undefined);
      registrarSignal.set({
        ...originalRegistrar!,
        allowedEppClientCertificateHostnames: ['epp.stored.example'],
        revokedClientCertificateHashes: ['storedHash123'],
      });
      fixture.detectChanges();
      await fixture.whenStable();

      expect(fixture.nativeElement.textContent).not.toContain(
        'Allowed EPP Client Certificate Hostnames'
      );
      expect(fixture.nativeElement.textContent).not.toContain(
        'Revoked Client Certificate Hashes'
      );

      fixture.nativeElement
        .querySelector('button[aria-label="Edit security settings"]')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      fixture.nativeElement
        .querySelector('.settings-security__edit-save')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(saveSpy).toHaveBeenCalledWith({
        ipAddressAllowList: [{ value: '123.123.123.123' }],
      });
      const savedPayload = saveSpy.mock.calls[0][0];
      expect('allowedEppClientCertificateHostnames' in savedPayload).toBe(
        false
      );
      expect('revokedClientCertificateHashes' in savedPayload).toBe(false);
    } finally {
      registrarSignal.set(originalRegistrar);
    }
  });

  it('should add entries to initially empty lists and submit empty array when clearing revoked hashes', async () => {
    const registrarService = TestBed.inject(RegistrarService);
    const registrarSignal = registrarService.registrar as WritableSignal<
      Registrar | undefined
    >;
    const originalRegistrar = registrarSignal();
    try {
      userDataSignal.set({
        ...DEFAULT_USER_DATA,
        allowEppClientCertHostnameAuth: true,
      });
      registrarSignal.set({
        ...originalRegistrar!,
        allowedEppClientCertificateHostnames: undefined,
        revokedClientCertificateHashes: ['revokedHashToClear'],
      });
      fixture.detectChanges();
      await fixture.whenStable();

      fixture.nativeElement
        .querySelector('button[aria-label="Edit security settings"]')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      // Add first hostname to an initially empty list.
      fixture.nativeElement
        .querySelector(
          'button[aria-label="Add new allowed certificate hostname"]'
        )
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      const hostnameInput = fixture.nativeElement.querySelector(
        '.console-app__allowedHostnameValue'
      );
      hostnameInput.value = 'epp.first.example';
      hostnameInput.dispatchEvent(new Event('input'));

      // Remove the only revoked hash so [] is explicitly submitted to clear it.
      fixture.nativeElement
        .querySelector('.console-app__removeRevokedHash')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      fixture.nativeElement
        .querySelector('.settings-security__edit-save')
        .click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(saveSpy).toHaveBeenCalledWith({
        ipAddressAllowList: [{ value: '123.123.123.123' }],
        allowedEppClientCertificateHostnames: [{ value: 'epp.first.example' }],
        revokedClientCertificateHashes: [],
      });
    } finally {
      registrarSignal.set(originalRegistrar);
    }
  });

  it('should display backend validation error when saving security settings fails', async () => {
    userDataSignal.set({
      ...DEFAULT_USER_DATA,
      allowEppClientCertHostnameAuth: true,
    });
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.nativeElement
      .querySelector('button[aria-label="Edit security settings"]')
      .click();
    fixture.detectChanges();
    await fixture.whenStable();

    const editDebugEl = fixture.debugElement.query(
      By.directive(SecurityEditComponent)
    );
    const snackBarSpy = vi
      .spyOn(editDebugEl.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    saveSpy.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error:
              'Cannot clear allowed EPP client certificate hostnames once configured',
          })
      )
    );

    fixture.nativeElement
      .querySelector('.settings-security__edit-save')
      .click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(snackBarSpy).toHaveBeenCalledWith(
      'Cannot clear allowed EPP client certificate hostnames once configured'
    );
    expect(securityServiceStub.isEditingSecurity()).toBe(true);
    expect(editDebugEl.componentInstance.isUpdating()).toBe(false);
    expect(TestBed.inject(GlobalLoaderService).isLoading()).toBe(false);
    expect(
      fixture.nativeElement.querySelector('.settings-security__edit-save')
        .disabled
    ).toBe(false);
  });

  it('should stop global loader on SecurityEditComponent destroy while save is pending', () => {
    vi.useFakeTimers();
    try {
      const editFixture = TestBed.createComponent(SecurityEditComponent);
      const snackBarSpy = vi
        .spyOn(editFixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      const editComponent = editFixture.componentInstance;
      editFixture.detectChanges();

      const globalLoader = TestBed.inject(GlobalLoaderService);
      const pending$ = new Subject<Registrar[]>();
      saveSpy.mockReturnValue(pending$.asObservable());

      editComponent.save();
      expect(globalLoader.isLoading()).toBe(true);

      editFixture.destroy();
      expect(globalLoader.isLoading()).toBe(false);

      vi.advanceTimersByTime(10000);
      expect(snackBarSpy).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('should stop global loader on EppPasswordEditComponent destroy while save is pending', () => {
    vi.useFakeTimers();
    try {
      const eppFixture = TestBed.createComponent(EppPasswordEditComponent);
      const snackBarSpy = vi
        .spyOn(eppFixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      const eppComponent = eppFixture.componentInstance;
      eppFixture.detectChanges();

      const globalLoader = TestBed.inject(GlobalLoaderService);
      const pending$ = new Subject<unknown>();
      saveEppPasswordSpy.mockReturnValue(pending$.asObservable());

      eppComponent.save({
        oldPassword: 'oldPass123',
        newPassword: 'newPass123',
        newPasswordRepeat: 'newPass123',
      });
      expect(globalLoader.isLoading()).toBe(true);

      eppFixture.destroy();
      expect(globalLoader.isLoading()).toBe(false);

      vi.advanceTimersByTime(10000);
      expect(snackBarSpy).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
