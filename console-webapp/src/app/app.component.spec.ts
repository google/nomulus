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
import { ComponentFixture, fakeAsync, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { AppComponent } from './app.component';
import { routes } from './app-routing.module';
import { AppModule } from './app.module';
import { PocReminderComponent } from './shared/components/pocReminder/pocReminder.component';
import { RouterModule } from '@angular/router';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { UserData, UserDataService } from './shared/services/userData.service';
import { Registrar, RegistrarService } from './registrar/registrar.service';
import { MatSidenavModule } from '@angular/material/sidenav';
import { signal, WritableSignal } from '@angular/core';

describe('AppComponent', () => {
  let component: AppComponent;
  let fixture: ComponentFixture<AppComponent>;
  let mockRegistrarService: {
    registrar: WritableSignal<Partial<Registrar> | null | undefined>;
    registrarId: WritableSignal<string>;
    registrars: WritableSignal<Array<Partial<Registrar>>>;
  };
  let mockUserDataService: { userData: WritableSignal<Partial<UserData>> };
  let mockSnackBar: jasmine.SpyObj<MatSnackBar>;

  const dummyPocReminderComponent = class {}; // Dummy class for type checking

  beforeEach(async () => {
    mockRegistrarService = {
      registrar: signal<Registrar | null | undefined>(undefined),
      registrarId: signal('123'),
      registrars: signal([]),
    };

    mockUserDataService = {
      userData: signal({}),
    };

    mockSnackBar = jasmine.createSpyObj('MatSnackBar', ['openFromComponent']);

    await TestBed.configureTestingModule({
      imports: [
        MatSidenavModule,
        NoopAnimationsModule,
        MatSnackBarModule,
        AppModule,
        RouterModule.forRoot(routes),
      ],
      providers: [
        { provide: RegistrarService, useValue: mockRegistrarService },
        { provide: UserDataService, useValue: mockUserDataService },
        { provide: MatSnackBar, useValue: mockSnackBar },
        { provide: PocReminderComponent, useClass: dummyPocReminderComponent },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    jasmine.clock().uninstall();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  describe('PoC Verification Reminder', () => {
    beforeEach(() => {
      jasmine.clock().install();
    });

    it('should open snackbar if lastPocVerificationDate is older than one year', fakeAsync(() => {
      const MOCK_TODAY = new Date('2024-07-15T10:00:00.000Z');
      jasmine.clock().mockDate(MOCK_TODAY);

      const twoYearsAgo = new Date(MOCK_TODAY);
      twoYearsAgo.setFullYear(MOCK_TODAY.getFullYear() - 2);

      mockRegistrarService.registrar.set({
        lastPocVerificationDate: twoYearsAgo.toISOString(),
      });

      fixture.detectChanges();
      TestBed.flushEffects();

      expect(mockSnackBar.openFromComponent).toHaveBeenCalledWith(
        PocReminderComponent,
        {
          horizontalPosition: 'center',
          verticalPosition: 'top',
          duration: 1000000000,
        }
      );
    }));

    it('should NOT open snackbar if lastPocVerificationDate is within last year', fakeAsync(() => {
      const MOCK_TODAY = new Date('2024-07-15T10:00:00.000Z');
      jasmine.clock().mockDate(MOCK_TODAY);

      const sixMonthsAgo = new Date(MOCK_TODAY);
      sixMonthsAgo.setMonth(MOCK_TODAY.getMonth() - 6);

      mockRegistrarService.registrar.set({
        lastPocVerificationDate: sixMonthsAgo.toISOString(),
      });

      fixture.detectChanges();
      TestBed.flushEffects();

      expect(mockSnackBar.openFromComponent).not.toHaveBeenCalled();
    }));
  });
});
