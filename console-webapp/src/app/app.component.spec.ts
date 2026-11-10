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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { AppComponent } from './app.component';
import { routes } from './app.routes';
import { PocReminderComponent } from './shared/components/pocReminder/pocReminder.component';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { UserData, UserDataService } from './shared/services/userData.service';
import { Registrar, RegistrarService } from './registrar/registrar.service';
import { MatSidenavModule } from '@angular/material/sidenav';
import { signal, WritableSignal } from '@angular/core';
import { BreakPointObserverService } from './shared/services/breakPoint.service';
import { Subject } from 'rxjs';

describe('AppComponent', () => {
  let component: AppComponent;
  let fixture: ComponentFixture<AppComponent>;
  let mockRegistrarService: {
    registrar: WritableSignal<Partial<Registrar> | null | undefined>;
    registrarId: WritableSignal<string>;
    registrars: WritableSignal<Array<Partial<Registrar>>>;
  };
  let mockUserDataService: { userData: WritableSignal<Partial<UserData>> };
  let mockBreakPointService: {
    isMobileView: WritableSignal<boolean>;
    isTabletView: WritableSignal<boolean>;
  };
  let openFromComponentSpy: ReturnType<typeof vi.spyOn>;

  const dummyPocReminderComponent = class {};

  beforeEach(async () => {
    mockRegistrarService = {
      registrar: signal<Registrar | null | undefined>(undefined),
      registrarId: signal('123'),
      registrars: signal([]),
    };

    mockUserDataService = {
      userData: signal({
        globalRole: 'NONE',
      }),
    };

    mockBreakPointService = {
      isMobileView: signal(false),
      isTabletView: signal(false),
    };

    await TestBed.configureTestingModule({
      imports: [
        MatSidenavModule,
        NoopAnimationsModule,
        MatSnackBarModule,
        AppComponent,
        RouterModule.forRoot(routes),
      ],
      providers: [
        { provide: RegistrarService, useValue: mockRegistrarService },
        { provide: UserDataService, useValue: mockUserDataService },
        {
          provide: BreakPointObserverService,
          useValue: mockBreakPointService,
        },
        { provide: PocReminderComponent, useClass: dummyPocReminderComponent },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    const snackBar = fixture.debugElement.injector.get(MatSnackBar);
    openFromComponentSpy = vi
      .spyOn(snackBar, 'openFromComponent')
      .mockReturnValue({} as any);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should create the app', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  describe('PoC Verification Reminder', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    it('should open snackbar if lastPocVerificationDate is older than one year for NONE role', () => {
      const MOCK_TODAY = new Date('2024-07-15T10:00:00.000Z');
      vi.setSystemTime(MOCK_TODAY);

      const twoYearsAgo = new Date(MOCK_TODAY);
      twoYearsAgo.setFullYear(MOCK_TODAY.getFullYear() - 2);

      mockRegistrarService.registrar.set({
        lastPocVerificationDate: twoYearsAgo.toISOString(),
      });

      fixture.detectChanges();
      TestBed.flushEffects();

      expect(openFromComponentSpy).toHaveBeenCalledWith(PocReminderComponent, {
        horizontalPosition: 'center',
        verticalPosition: 'top',
        duration: 1000000000,
      });
    });

    it('should NOT open snackbar if lastPocVerificationDate is within last year', () => {
      const MOCK_TODAY = new Date('2024-07-15T10:00:00.000Z');
      vi.setSystemTime(MOCK_TODAY);

      const sixMonthsAgo = new Date(MOCK_TODAY);
      sixMonthsAgo.setMonth(MOCK_TODAY.getMonth() - 6);

      mockRegistrarService.registrar.set({
        lastPocVerificationDate: sixMonthsAgo.toISOString(),
      });

      fixture.detectChanges();
      TestBed.flushEffects();

      expect(openFromComponentSpy).not.toHaveBeenCalled();
    });

    it('should NOT open snackbar if user has a support globalRole', () => {
      const MOCK_TODAY = new Date('2024-07-15T10:00:00.000Z');
      vi.setSystemTime(MOCK_TODAY);

      const twoYearsAgo = new Date(MOCK_TODAY);
      twoYearsAgo.setFullYear(MOCK_TODAY.getFullYear() - 2);

      mockUserDataService.userData.set({ globalRole: 'SUPPORT_AGENT' });
      mockRegistrarService.registrar.set({
        lastPocVerificationDate: twoYearsAgo.toISOString(),
      });

      fixture.detectChanges();
      TestBed.flushEffects();

      expect(openFromComponentSpy).not.toHaveBeenCalled();
    });
  });

  describe('Sidenav behavior', () => {
    it('should toggle sidenav open and closed', () => {
      fixture.detectChanges();
      const closeSpy = vi.spyOn(component.sidenav, 'close');
      const openSpy = vi.spyOn(component.sidenav, 'open');

      Object.defineProperty(component.sidenav, 'opened', {
        value: true,
        configurable: true,
      });
      component.toggleSidenav();
      expect(closeSpy).toHaveBeenCalled();

      Object.defineProperty(component.sidenav, 'opened', {
        value: false,
        configurable: true,
      });
      component.toggleSidenav();
      expect(openSpy).toHaveBeenCalled();
    });

    it('should close sidenav on NavigationEnd when in mobile view', () => {
      const router = TestBed.inject(Router);
      const eventsSubject = router.events as unknown as Subject<any>;
      fixture.detectChanges();

      const closeSpy = vi.spyOn(component.sidenav, 'close');
      mockBreakPointService.isMobileView.set(true);
      eventsSubject.next(new NavigationEnd(1, '/home', '/home'));

      expect(closeSpy).toHaveBeenCalled();
    });

    it('should NOT close sidenav on NavigationEnd when in desktop view', () => {
      const router = TestBed.inject(Router);
      const eventsSubject = router.events as unknown as Subject<any>;
      fixture.detectChanges();

      const closeSpy = vi.spyOn(component.sidenav, 'close');
      mockBreakPointService.isMobileView.set(false);
      eventsSubject.next(new NavigationEnd(1, '/home', '/home'));

      expect(closeSpy).not.toHaveBeenCalled();
    });
  });
});
