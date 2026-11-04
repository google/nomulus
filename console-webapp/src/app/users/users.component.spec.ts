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
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { MatSelectChange } from '@angular/material/select';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import { UserDataService } from '../shared/services/userData.service';
import { roleToDescription, User, UsersService } from './users.service';
import { UsersComponent } from './users.component';
import { UserDetailsComponent } from './userDetails.component';
import { UsersListComponent, columns } from './usersList.component';
import { UserEditFormComponent } from './userEditForm.component';

describe('Users Feature (UsersService, UsersComponent, UserDetailsComponent, UsersListComponent, UserEditFormComponent)', () => {
  let backendService: BackendService;
  let registrarIdSignal: ReturnType<typeof signal<string>>;

  const sampleUser: User = {
    emailAddress: 'alice@example.com',
    role: 'ACCOUNT_MANAGER',
    registryLockEmailAddress: 'alice-lock@example.com',
  };

  beforeEach(async () => {
    registrarIdSignal = signal('reg-1');

    await TestBed.configureTestingModule({
      imports: [
        UsersComponent,
        UserDetailsComponent,
        UsersListComponent,
        UserEditFormComponent,
        MaterialModule,
        NoopAnimationsModule,
      ],
      providers: [
        BackendService,
        UsersService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: registrarIdSignal,
            registrars: signal([
              { registrarId: 'reg-1' },
              { registrarId: 'reg-2' },
            ]),
            registrar: signal({ registryLockAllowed: true }),
          },
        },
        {
          provide: UserDataService,
          useValue: {
            userData: signal({ globalRole: 'SUPPORT_ADMIN', isAdmin: true }),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    backendService = TestBed.inject(BackendService);
    vi.spyOn(backendService, 'getUsers').mockReturnValue(of([sampleUser]));
  });

  it('should map user roles to human-readable descriptions', () => {
    expect(roleToDescription('')).toBe('N/A');
    expect(roleToDescription('ACCOUNT_MANAGER')).toBe('Viewer');
    expect(roleToDescription('PRIMARY_CONTACT')).toBe('Editor');
  });

  it('should load users in UsersComponent, create users, and import existing users from another registrar', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(backendService.getUsers).toHaveBeenCalledWith('reg-1');

    const newUser: User = {
      emailAddress: 'bob@example.com',
      role: 'TECH_CONTACT',
    };
    const createSpy = vi
      .spyOn(backendService, 'createUser')
      .mockReturnValue(of(newUser));

    component.createNewUser(newUser);
    expect(createSpy).toHaveBeenCalledWith('reg-1', newUser);
    expect((component as any).usersService.currentlyOpenUserEmail()).toBe(
      'bob@example.com'
    );

    component.addExistingUser();
    expect(component.selectingExistingUser()).toBe(true);

    component.onRegistrarSelectionChange({ value: 'reg-2' } as MatSelectChange);
    expect(backendService.getUsers).toHaveBeenCalledWith('reg-2');
    expect(component.usersSelection()).toEqual([sampleUser]);

    component.existingUserSelected(sampleUser);
    component.submitExistingUser();
    expect(createSpy).toHaveBeenCalledWith('reg-1', sampleUser);
    expect(component.selectingExistingUser()).toBe(false);
  });

  it('should display user details, update user, and delete user in UserDetailsComponent', () => {
    const usersService = TestBed.inject(UsersService);
    usersService.users.set([sampleUser]);
    usersService.currentlyOpenUserEmail.set('alice@example.com');
    usersService.isNewUser = true;

    const fixture = TestBed.createComponent(UserDetailsComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.isNewUser).toBe(true);
    expect(usersService.isNewUser).toBe(false);
    expect(component.userDetails()).toEqual(sampleUser);
    expect(component.roleToDescription('ACCOUNT_MANAGER')).toBe('Viewer');

    const updateSpy = vi
      .spyOn(backendService, 'updateUser')
      .mockReturnValue(of(sampleUser));
    component.isEditing.set(true);
    component.saveEdit({ ...sampleUser, role: 'PRIMARY_CONTACT' });
    expect(updateSpy).toHaveBeenCalled();
    expect(component.isEditing()).toBe(false);

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const deleteSpy = vi
      .spyOn(backendService, 'deleteUser')
      .mockReturnValue(of({}));
    component.deleteUser();
    expect(deleteSpy).toHaveBeenCalledWith('reg-1', sampleUser);
    expect(usersService.currentlyOpenUserEmail()).toBe('');
    confirmSpy.mockRestore();
  });

  it('should render rows and emit selection in UsersListComponent', () => {
    const fixture = TestBed.createComponent(UsersListComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('users', [sampleUser]);
    fixture.detectChanges();

    expect(component.dataSource.data).toEqual([sampleUser]);
    expect(columns[0].cell(sampleUser)).toBe('alice@example.com');
    expect(columns[1].cell(sampleUser)).toBe('Viewer');

    let selected: User | undefined;
    component.onSelect.subscribe((u) => (selected = u));
    component.onClick(sampleUser);

    expect(selected).toEqual(sampleUser);
    expect(component.isRowSelected(sampleUser)).toBe(true);
  });

  it('should validate form and trigger registry lock password reset in UserEditFormComponent', () => {
    const fixture = TestBed.createComponent(UserEditFormComponent);
    const snackBarSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    const dialogSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(true) } as any);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('user', sampleUser);
    fixture.detectChanges();

    let emittedUser: User | undefined;
    component.onEditComplete.subscribe((u) => (emittedUser = u));

    component.form = {
      nativeElement: {
        checkValidity: vi.fn().mockReturnValue(true),
        reportValidity: vi.fn(),
      },
    };
    component.saveEdit({ preventDefault: vi.fn() } as unknown as SubmitEvent);
    expect(emittedUser).toEqual(sampleUser);

    const resetSpy = vi
      .spyOn(backendService, 'requestRegistryLockPasswordReset')
      .mockReturnValueOnce(of({}))
      .mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'Reset failed' }))
      );

    component.requestRegistryLockPasswordReset();
    expect(dialogSpy).toHaveBeenCalled();
    expect(resetSpy).toHaveBeenCalledWith('reg-1', 'alice-lock@example.com');

    component.requestRegistryLockPasswordReset();
    expect(snackBarSpy).toHaveBeenCalledWith('Reset failed');
  });
});
