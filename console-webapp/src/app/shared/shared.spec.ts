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

import { Component, signal } from '@angular/core';
import { Location } from '@angular/common';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar, MatSnackBarRef } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { PasswordInputForm } from './components/passwordReset/passwordInputForm.component';
import { PasswordResetVerifyComponent } from './components/passwordReset/passwordResetVerify.component';
import { PocReminderComponent } from './components/pocReminder/pocReminder.component';
import { ForceFocusDirective } from './directives/forceFocus.directive';
import { LocationBackDirective } from './directives/locationBack.directive';
import {
  RESTRICTED_ELEMENTS,
  UserLevelVisibility,
} from './directives/userLevelVisiblity.directive';
import { BackendService } from './services/backend.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from './services/globalLoader.service';
import { UserData, UserDataService } from './services/userData.service';

@Component({
  imports: [UserLevelVisibility, LocationBackDirective, ForceFocusDirective],
  template: `
    <div
      id="restricted"
      [elementId]="restrictedElement"
      [isReverse]="isReverse()"
    ></div>
    <button id="backBtn" backButton>Back</button>
    <input id="focusInput" forceFocus />
  `,
})
class TestHostComponent {
  restrictedElement: RESTRICTED_ELEMENTS | null =
    RESTRICTED_ELEMENTS.REGISTRAR_ELEMENT;
  isReverse = signal(false);
}

describe('Shared Services, Directives & Components', () => {
  let httpMock: HttpTestingController;
  let backendService: BackendService;
  let userDataSignal: ReturnType<typeof signal<Partial<UserData> | undefined>>;

  beforeEach(async () => {
    userDataSignal = signal<Partial<UserData> | undefined>({
      globalRole: 'NONE',
    });

    await TestBed.configureTestingModule({
      imports: [
        TestHostComponent,
        PasswordInputForm,
        PasswordResetVerifyComponent,
        PocReminderComponent,
        MaterialModule,
      ],
      providers: [
        BackendService,
        GlobalLoaderService,
        {
          provide: UserDataService,
          useValue: { userData: userDataSignal },
        },
        {
          provide: RegistrarService,
          useValue: {
            registrarId: signal('reg-1'),
            registrar: signal({
              registrarId: 'reg-1',
              registrarName: 'Reg One',
            }),
            updateRegistrar: vi.fn().mockReturnValue(of({})),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: of(
              convertToParamMap({
                resetRequestVerificationCode: 'reset-code-999',
              })
            ),
          },
        },
        {
          provide: MatSnackBarRef,
          useValue: { dismiss: vi.fn() },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    backendService = TestBed.inject(BackendService);
  });

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  describe('BackendService', () => {
    it('should construct query URLs and send payloads for core API endpoints', () => {
      backendService
        .getDomains('reg-1', '2024-01-01T00:00:00Z', 2, 25, 100, 'example')
        .subscribe();
      const domainReq = httpMock.expectOne(
        '/console-api/domain-list?registrarId=reg-1&checkpointTime=2024-01-01T00:00:00Z&pageNumber=2&resultsPerPage=25&totalResults=100&searchTerm=example'
      );
      expect(domainReq.request.method).toBe('GET');
      domainReq.flush({ checkpointTime: '', domains: [], totalResults: 0 });

      backendService.getHistoryLog('reg-1', 'user@example.com').subscribe();
      const histReq = httpMock.expectOne(
        '/console-api/history?registrarId=reg-1&consoleUserEmail=user@example.com'
      );
      expect(histReq.request.method).toBe('GET');
      histReq.flush([]);

      backendService
        .bulkDomainAction(['a.tld'], 'reason', 'DELETE', 'reg-1')
        .subscribe();
      const bulkReq = httpMock.expectOne(
        '/console-api/bulk-domain?registrarId=reg-1&bulkDomainAction=DELETE'
      );
      expect(bulkReq.request.method).toBe('POST');
      expect(bulkReq.request.body).toEqual({
        domainList: ['a.tld'],
        reason: 'reason',
      });
      bulkReq.flush({});
    });

    it('should return mockData or rethrow errors in errorCatcher', () => {
      const consoleSpy = vi
        .spyOn(console, 'error')
        .mockImplementation(() => {});
      const err = new HttpErrorResponse({
        status: 500,
        error: 'Internal failure',
      });

      let fallbackResult: string | undefined;
      backendService
        .errorCatcher(err, 'fallback')
        .subscribe((res) => (fallbackResult = res));
      expect(fallbackResult).toBe('fallback');

      let caughtError: HttpErrorResponse | undefined;
      backendService.errorCatcher(err).subscribe({
        error: (e) => (caughtError = e),
      });
      expect(caughtError).toBe(err);
      consoleSpy.mockRestore();
    });
  });

  describe('GlobalLoaderService', () => {
    it('should track active loaders and trigger loadingTimeout after 10 seconds', () => {
      vi.useFakeTimers();
      const loaderService = TestBed.inject(GlobalLoaderService);
      const mockLoader: GlobalLoader = { loadingTimeout: vi.fn() };

      loaderService.startGlobalLoader(mockLoader);
      expect(loaderService.isLoading()).toBe(true);

      loaderService.stopGlobalLoader(mockLoader);
      expect(loaderService.isLoading()).toBe(false);

      loaderService.startGlobalLoader(mockLoader);
      vi.advanceTimersByTime(10000);
      expect(mockLoader.loadingTimeout).toHaveBeenCalled();
      expect(loaderService.isLoading()).toBe(false);
    });
  });

  describe('Directives (UserLevelVisibility, LocationBackDirective, ForceFocusDirective)', () => {
    it('should toggle display based on user globalRole and isReverse', () => {
      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.detectChanges();
      const restrictedEl: HTMLElement =
        fixture.nativeElement.querySelector('#restricted');

      // NONE role disables REGISTRAR_ELEMENT
      expect(restrictedEl.style.display).toBe('none');

      userDataSignal.set({ globalRole: 'SUPPORT_LEAD' });
      TestBed.flushEffects();
      expect(restrictedEl.style.display).toBe('');

      fixture.componentInstance.isReverse.set(true);
      fixture.detectChanges();
      userDataSignal.set({ globalRole: 'NONE' });
      TestBed.flushEffects();
      expect(restrictedEl.style.display).toBe('');
    });

    it('should call Location.back() on click and focus forceFocus element', () => {
      const location = TestBed.inject(Location);
      const backSpy = vi.spyOn(location, 'back').mockImplementation(() => {});

      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.detectChanges();

      const backBtn: HTMLButtonElement =
        fixture.nativeElement.querySelector('#backBtn');
      backBtn.click();
      expect(backSpy).toHaveBeenCalled();

      const focusInput: HTMLInputElement =
        fixture.nativeElement.querySelector('#focusInput');
      expect(focusInput.tabIndex).toBe(-1);
    });
  });

  describe('PasswordInputForm, PasswordResetVerifyComponent & PocReminderComponent', () => {
    it('should validate password matching and emit results in PasswordInputForm', () => {
      vi.useFakeTimers();
      const formGroup = new FormGroup({
        oldPassword: new FormControl('oldPass1'),
        newPassword: new FormControl('newPass1', [
          Validators.required,
          PasswordInputForm.newPasswordsMatch,
        ]),
        newPasswordRepeat: new FormControl('mismatch', [
          Validators.required,
          PasswordInputForm.newPasswordsMatch,
        ]),
      });

      const fixture = TestBed.createComponent(PasswordInputForm);
      const component = fixture.componentInstance;
      fixture.componentRef.setInput('formGroup', formGroup);
      fixture.componentRef.setInput('displayOldPasswordField', true);
      fixture.detectChanges();

      formGroup.get('newPasswordRepeat')?.updateValueAndValidity();
      vi.runAllTimers();
      expect(component.hasError('newPasswordRepeat')).toBe(
        "Passwords don't match"
      );

      formGroup.get('newPasswordRepeat')?.setValue('newPass1');
      formGroup.get('newPasswordRepeat')?.updateValueAndValidity();
      expect(component.hasError('newPasswordRepeat')).toBe('');

      let emitted: any;
      component.submitResults.subscribe((res) => (emitted = res));
      component.save();
      expect(emitted).toEqual({
        oldPassword: 'oldPass1',
        newPassword: 'newPass1',
        newPasswordRepeat: 'newPass1',
      });
    });

    it('should verify password reset token and finalize password reset', () => {
      const router = TestBed.inject(Router);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      vi.spyOn(backendService, 'getPasswordResetInformation').mockReturnValue(
        of({ registrarId: 'reg-reset', type: 'EPP' })
      );
      const finalizeSpy = vi
        .spyOn(backendService, 'finalizePasswordReset')
        .mockReturnValue(of({}));

      const fixture = TestBed.createComponent(PasswordResetVerifyComponent);
      const snackBarSpy = vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.isLoading()).toBe(false);
      expect(component.type).toBe('EPP');

      component.save({
        oldPassword: null,
        newPassword: 'newSecurePassword',
        newPasswordRepeat: 'newSecurePassword',
      });
      expect(finalizeSpy).toHaveBeenCalledWith(
        'reset-code-999',
        'newSecurePassword'
      );
      expect(navigateSpy).toHaveBeenCalledWith(['']);
      expect(snackBarSpy).toHaveBeenCalledWith(
        'Password reset completed successfully'
      );
    });

    it('should update registrar lastPocVerificationDate and dismiss snackbar in PocReminderComponent', () => {
      const registrarService = TestBed.inject(RegistrarService);
      const snackBarRef = TestBed.inject(MatSnackBarRef);

      const fixture = TestBed.createComponent(PocReminderComponent);
      const snackBarSpy = vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      component.confirmReviewed();
      expect(registrarService.updateRegistrar).toHaveBeenCalledWith(
        expect.objectContaining({
          registrarId: 'reg-1',
          lastPocVerificationDate: expect.any(String),
        })
      );
      expect(snackBarRef.dismiss).toHaveBeenCalled();

      (registrarService.updateRegistrar as any).mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'PoC update error' }))
      );
      component.confirmReviewed();
      expect(snackBarSpy).toHaveBeenCalledWith('PoC update error');
    });
  });
});
