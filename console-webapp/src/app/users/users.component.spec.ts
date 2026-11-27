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

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSelectChange } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSort } from '@angular/material/sort';
import { of, Subject, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { BackendService } from '../shared/services/backend.service';
import { UserDataService } from '../shared/services/userData.service';
import { UserDetailsComponent } from './userDetails.component';
import { UserEditFormComponent } from './userEditForm.component';
import { UsersComponent } from './users.component';
import { roleToDescription, User, UsersService } from './users.service';
import { columns, UsersListComponent } from './usersList.component';

describe('Users Feature (UsersService, UsersComponent, UserDetailsComponent, UsersListComponent, UserEditFormComponent)', () => {
  let backendService: BackendService;
  let registrarIdSignal: ReturnType<typeof signal<string>>;

  const sampleUser: User = {
    emailAddress: 'alice@example.com',
    role: 'ACCOUNT_MANAGER',
    registryLockEmailAddress: 'alice-lock@example.com',
  };

  const otherUser: User = {
    emailAddress: 'charlie@example.com',
    role: 'TECH_CONTACT',
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
              { registrarId: 'reg-3' },
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

  it('should return to the users list (not New User Form) when clicking Back after creating a user', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    expect(backendService.getUsers).toHaveBeenCalledWith('reg-1');
    expect(usersService.viewState()).toEqual({ kind: 'list' });

    component.openCreateNew();
    fixture.detectChanges();
    expect(usersService.viewState()).toEqual({
      kind: 'create-new',
      saving: false,
    });
    expect(fixture.nativeElement.textContent).toContain('New User Form');

    const newUser: User = {
      emailAddress: 'bob.reg-1@example.com',
      role: 'TECH_CONTACT',
      password: 'one-time-password',
    };
    const createSpy = vi
      .spyOn(backendService, 'createUser')
      .mockReturnValue(of(newUser));

    component.createNewUser(newUser);
    fixture.detectChanges();

    expect(createSpy).toHaveBeenCalledWith('reg-1', newUser);
    expect(usersService.viewState()).toEqual({
      kind: 'detail',
      email: 'bob.reg-1@example.com',
      isNewlyCreated: true,
    });
    expect(fixture.nativeElement.textContent).toContain(
      'bob.reg-1@example.com successfully created'
    );
    expect(fixture.nativeElement.textContent).toContain(
      'Please save the password.'
    );

    // Clicking Back from the created-user detail view must return to list, not create-new.
    const backBtn: HTMLButtonElement | null =
      fixture.nativeElement.querySelector(
        'button[aria-label="Back to users list"]'
      );
    expect(backBtn).not.toBeNull();
    backBtn!.click();
    fixture.detectChanges();

    expect(usersService.viewState()).toEqual({ kind: 'list' });
    expect(fixture.nativeElement.textContent).not.toContain('New User Form');
    expect(fixture.nativeElement.textContent).toContain('Create New User');
  });

  it('should guard null selection and avoid created-user password reveal when adding an existing user', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    component.addExistingUser();
    fixture.detectChanges();

    // Submitting with no selected user must be a no-op and never trap the UI in loading.
    const createSpy = vi
      .spyOn(backendService, 'createUser')
      .mockReturnValue(of(null as unknown as User));
    component.submitExistingUser();
    expect(createSpy).not.toHaveBeenCalled();
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: '',
      candidates: { status: 'idle' },
      selectedUser: null,
      submitting: false,
    });

    vi.spyOn(backendService, 'getUsers')
      .mockReturnValueOnce(of([otherUser]))
      .mockReturnValueOnce(of([sampleUser, otherUser]));

    component.onRegistrarSelectionChange({ value: 'reg-2' } as MatSelectChange);
    fixture.detectChanges();
    expect(backendService.getUsers).toHaveBeenCalledWith('reg-2');
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: 'reg-2',
      candidates: { status: 'loaded', data: [otherUser] },
      selectedUser: null,
      submitting: false,
    });

    component.existingUserSelected(otherUser);
    component.submitExistingUser();
    fixture.detectChanges();

    expect(createSpy).toHaveBeenCalledWith('reg-1', otherUser);
    // Must land back on the users list and NEVER display the new-user password reveal banner.
    expect(usersService.viewState()).toEqual({ kind: 'list' });
    expect(fixture.nativeElement.textContent).not.toContain(
      'successfully created'
    );
    expect(fixture.nativeElement.textContent).not.toContain(
      'Please save the password'
    );
  });

  it('should clear stale selectedExistingUser and cancel in-flight fetch when switching source registrar', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    component.addExistingUser();
    const reg2Users$ = new Subject<User[]>();
    const reg3Users$ = new Subject<User[]>();
    vi.spyOn(backendService, 'getUsers')
      .mockReturnValueOnce(of([sampleUser]))
      .mockReturnValueOnce(reg2Users$.asObservable())
      .mockReturnValueOnce(reg3Users$.asObservable());

    // First select reg-1 and pick sampleUser
    component.onRegistrarSelectionChange({ value: 'reg-1' } as MatSelectChange);
    component.existingUserSelected(sampleUser);
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: 'reg-1',
      candidates: { status: 'loaded', data: [sampleUser] },
      selectedUser: sampleUser,
      submitting: false,
    });

    // Switch source registrar to reg-2 while request is pending: selectedUser must clear immediately
    component.onRegistrarSelectionChange({ value: 'reg-2' } as MatSelectChange);
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: 'reg-2',
      candidates: { status: 'loading' },
      selectedUser: null,
      submitting: false,
    });
    expect(reg2Users$.observed).toBe(true);

    // Switch again to reg-3 before reg-2 resolves: reg-2 subscription must cancel
    component.onRegistrarSelectionChange({ value: 'reg-3' } as MatSelectChange);
    expect(reg2Users$.observed).toBe(false);
    reg2Users$.next([sampleUser]);
    reg2Users$.complete();
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: 'reg-3',
      candidates: { status: 'loading' },
      selectedUser: null,
      submitting: false,
    });

    reg3Users$.next([otherUser]);
    reg3Users$.complete();
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: 'reg-3',
      candidates: { status: 'loaded', data: [otherUser] },
      selectedUser: null,
      submitting: false,
    });

    // Clearing the source registrar dropdown resets to idle with null selection
    component.onRegistrarSelectionChange({ value: '' } as MatSelectChange);
    expect(usersService.viewState()).toEqual({
      kind: 'add-existing',
      sourceRegistrarId: '',
      candidates: { status: 'idle' },
      selectedUser: null,
      submitting: false,
    });
  });

  it('should display error state with retry when loading users fails', () => {
    vi.spyOn(backendService, 'getUsers').mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ error: 'Users fetch failed' }))
    );

    const fixture = TestBed.createComponent(UsersComponent);
    const snackBarSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    expect(snackBarSpy).toHaveBeenCalledWith('Users fetch failed');
    expect(usersService.viewState()).toEqual({
      kind: 'error',
      message: 'Users fetch failed',
    });
    expect(fixture.nativeElement.textContent).toContain('Users fetch failed');

    vi.spyOn(backendService, 'getUsers').mockReturnValueOnce(of([sampleUser]));
    fixture.componentInstance.loadUsers();
    fixture.detectChanges();
    expect(usersService.viewState()).toEqual({ kind: 'list' });
  });

  it('should display user details, toggle password signal, update user, and delete user in UserDetailsComponent', () => {
    const usersService = TestBed.inject(UsersService);
    const createdUser: User = { ...sampleUser, password: 'secret-password' };
    usersService.users.set([createdUser]);
    usersService.openUserDetails('alice@example.com', true);

    const fixture = TestBed.createComponent(UserDetailsComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.isNewlyCreated()).toBe(true);
    expect(component.userDetails()).toEqual(createdUser);
    expect(component.roleToDescription('ACCOUNT_MANAGER')).toBe('Viewer');

    const passwordInput: HTMLInputElement = fixture.nativeElement.querySelector(
      'input[aria-label="Password field"]'
    );
    expect(passwordInput.type).toBe('password');
    component.togglePasswordVisibility();
    fixture.detectChanges();
    expect(component.isPasswordVisible()).toBe(true);
    expect(passwordInput.type).toBe('text');

    const updateSubject$ = new Subject<User>();
    const updateSpy = vi
      .spyOn(backendService, 'updateUser')
      .mockReturnValue(updateSubject$.asObservable());
    component.isEditing.set(true);
    fixture.detectChanges();
    expect(component.isEditing()).toBe(true);

    component.saveEdit({ ...sampleUser, role: 'PRIMARY_CONTACT' });
    fixture.detectChanges();
    expect(component.isEditing()).toBe(true);
    expect(component.isLoading()).toBe(true);
    // Progress bar must render while editing save is in flight
    expect(
      fixture.nativeElement.querySelector('mat-progress-bar')
    ).not.toBeNull();

    updateSubject$.next(sampleUser);
    updateSubject$.complete();
    fixture.detectChanges();
    expect(updateSpy).toHaveBeenCalled();
    expect(component.isEditing()).toBe(false);
    expect(component.isLoading()).toBe(false);
    expect(component.isNewlyCreated()).toBe(false);

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const deleteSpy = vi
      .spyOn(backendService, 'deleteUser')
      .mockReturnValue(of({}));
    component.deleteUser();
    expect(deleteSpy).toHaveBeenCalledWith('reg-1', sampleUser);
    expect(usersService.viewState()).toEqual({ kind: 'list' });
    confirmSpy.mockRestore();
  });

  it('should render rows and emit selection in UsersListComponent', () => {
    const fixture = TestBed.createComponent(UsersListComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('users', [sampleUser]);
    fixture.detectChanges();

    expect(component.dataSource.data).toEqual([sampleUser]);
    expect(component.dataSource.sort).toBeTruthy();
    expect(columns[0].cell(sampleUser)).toBe('alice@example.com');
    expect(columns[1].cell(sampleUser)).toBe('Viewer');

    let selected: User | undefined;
    component.onSelect.subscribe((u) => (selected = u));
    component.onClick(sampleUser);

    expect(selected).toEqual(sampleUser);
    expect(component.isRowSelected(sampleUser)).toBe(true);

    const editorUser: User = {
      emailAddress: 'bob@example.com',
      role: 'TECH_CONTACT',
    };
    const sortedByRole = component.dataSource.sortData(
      [sampleUser, editorUser],
      { active: 'role', direction: 'asc' } as MatSort
    );
    expect(sortedByRole.map((u) => u.emailAddress)).toEqual([
      'bob@example.com',
      'alice@example.com',
    ]);
  });

  it('should reset cached users, cancel in-flight mutations, and reset viewState when switching registrars', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    const pendingCreate$ = new Subject<User>();
    vi.spyOn(backendService, 'createUser').mockReturnValueOnce(
      pendingCreate$.asObservable()
    );
    component.openCreateNew();
    component.createNewUser(otherUser);
    expect(pendingCreate$.observed).toBe(true);

    const pendingReg2$ = new Subject<User[]>();
    const pendingReg3$ = new Subject<User[]>();
    vi.spyOn(backendService, 'getUsers')
      .mockReturnValueOnce(pendingReg2$.asObservable())
      .mockReturnValueOnce(pendingReg3$.asObservable());

    registrarIdSignal.set('reg-2');
    TestBed.flushEffects();

    // Switching registrar cancels in-flight createUser mutation
    expect(pendingCreate$.observed).toBe(false);
    expect(usersService.viewState()).toEqual({ kind: 'loading' });
    expect(usersService.users()).toEqual([]);
    expect(pendingReg2$.observed).toBe(true);

    // Late emission from cancelled createUser must be ignored
    pendingCreate$.next(otherUser);
    pendingCreate$.complete();
    expect(usersService.viewState()).toEqual({ kind: 'loading' });
    expect(usersService.users()).toEqual([]);

    // Switch to reg-3 before reg-2 resolves; stale reg-2 response must be ignored
    registrarIdSignal.set('reg-3');
    TestBed.flushEffects();
    expect(pendingReg2$.observed).toBe(false);

    pendingReg2$.next([sampleUser]);
    pendingReg2$.complete();
    expect(usersService.viewState()).toEqual({ kind: 'loading' });
    expect(usersService.users()).toEqual([]);

    const reg3User: User = {
      emailAddress: 'bob@example.com',
      role: 'TECH_CONTACT',
    };
    pendingReg3$.next([reg3User]);
    pendingReg3$.complete();

    expect(usersService.viewState()).toEqual({ kind: 'list' });
    expect(usersService.users()).toEqual([reg3User]);
  });

  it('should not fire loadUsers when cancelling in-flight submitExistingUser on registrar switch or destroy', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const component = fixture.componentInstance;
    const usersService = fixture.debugElement.injector.get(UsersService);
    fixture.detectChanges();

    const getUsersSpy = vi.spyOn(backendService, 'getUsers');
    component.addExistingUser();
    getUsersSpy.mockReturnValueOnce(of([otherUser]));
    component.onRegistrarSelectionChange({ value: 'reg-2' } as MatSelectChange);
    component.existingUserSelected(otherUser);

    const pendingAdd$ = new Subject<User>();
    vi.spyOn(backendService, 'createUser').mockReturnValueOnce(
      pendingAdd$.asObservable()
    );
    component.submitExistingUser();
    expect(pendingAdd$.observed).toBe(true);

    // Clearing registrarId cancels submitExistingUser without invoking loadUsers()
    getUsersSpy.mockClear();
    registrarIdSignal.set('');
    TestBed.flushEffects();

    expect(pendingAdd$.observed).toBe(false);
    expect(getUsersSpy).not.toHaveBeenCalled();
    expect(usersService.viewState()).toEqual({ kind: 'list' });

    // Switching registrar to reg-2 triggers exactly one loadUsers('reg-2'), never a duplicate
    const pendingReg2Load$ = new Subject<User[]>();
    getUsersSpy.mockReturnValueOnce(of([sampleUser]));
    registrarIdSignal.set('reg-1');
    TestBed.flushEffects();

    component.addExistingUser();
    getUsersSpy.mockReturnValueOnce(of([otherUser]));
    component.onRegistrarSelectionChange({ value: 'reg-2' } as MatSelectChange);
    component.existingUserSelected(otherUser);

    const pendingAdd2$ = new Subject<User>();
    vi.spyOn(backendService, 'createUser').mockReturnValueOnce(
      pendingAdd2$.asObservable()
    );
    component.submitExistingUser();
    expect(pendingAdd2$.observed).toBe(true);

    getUsersSpy.mockClear();
    getUsersSpy.mockReturnValueOnce(pendingReg2Load$.asObservable());
    registrarIdSignal.set('reg-2');
    TestBed.flushEffects();

    expect(pendingAdd2$.observed).toBe(false);
    expect(getUsersSpy).toHaveBeenCalledTimes(1);
    expect(getUsersSpy).toHaveBeenCalledWith('reg-2');
    expect(usersService.viewState()).toEqual({ kind: 'loading' });

    // Destroying the component while addExistingUser is in flight must also never call loadUsers()
    pendingReg2Load$.next([otherUser]);
    pendingReg2Load$.complete();
    component.addExistingUser();
    getUsersSpy.mockReturnValueOnce(of([sampleUser]));
    component.onRegistrarSelectionChange({ value: 'reg-1' } as MatSelectChange);
    component.existingUserSelected(sampleUser);

    const pendingAdd3$ = new Subject<User>();
    vi.spyOn(backendService, 'createUser').mockReturnValueOnce(
      pendingAdd3$.asObservable()
    );
    component.submitExistingUser();
    getUsersSpy.mockClear();
    fixture.destroy();

    expect(pendingAdd3$.observed).toBe(false);
    expect(getUsersSpy).not.toHaveBeenCalled();
  });

  it('should not clobber UsersService viewState when UserDetailsComponent is destroyed mid-delete or mid-save', () => {
    const usersService = TestBed.inject(UsersService);
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    // 1. Mid-delete teardown (e.g., registrar switch unmounting <app-user-edit>)
    usersService.users.set([sampleUser]);
    usersService.openUserDetails(sampleUser.emailAddress, false);
    const deleteFixture = TestBed.createComponent(UserDetailsComponent);
    deleteFixture.detectChanges();

    const pendingDelete$ = new Subject<any>();
    vi.spyOn(backendService, 'deleteUser').mockReturnValueOnce(
      pendingDelete$.asObservable()
    );
    deleteFixture.componentInstance.deleteUser();
    expect(pendingDelete$.observed).toBe(true);

    // Simulate parent UsersComponent transitioning to loading for a newly selected registrar
    usersService.viewState.set({ kind: 'loading' });
    deleteFixture.destroy();

    expect(pendingDelete$.observed).toBe(false);
    expect(usersService.viewState()).toEqual({ kind: 'loading' });

    // 2. Mid-save teardown must not overwrite { kind: 'loading' } back to { kind: 'detail' }
    usersService.users.set([sampleUser]);
    usersService.openUserDetails(sampleUser.emailAddress, false);
    const editFixture = TestBed.createComponent(UserDetailsComponent);
    editFixture.detectChanges();

    const pendingUpdate$ = new Subject<User>();
    vi.spyOn(backendService, 'updateUser').mockReturnValueOnce(
      pendingUpdate$.asObservable()
    );
    editFixture.componentInstance.isEditing.set(true);
    editFixture.componentInstance.saveEdit({
      ...sampleUser,
      role: 'TECH_CONTACT',
    });
    expect(pendingUpdate$.observed).toBe(true);

    usersService.viewState.set({ kind: 'loading' });
    editFixture.destroy();

    expect(pendingUpdate$.observed).toBe(false);
    expect(usersService.viewState()).toEqual({ kind: 'loading' });
    confirmSpy.mockRestore();
  });

  it('should hide Reset registry lock password button in new-user mode and when no lock email exists', () => {
    const fixture = TestBed.createComponent(UserEditFormComponent);
    const dialogSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(true) } as any);

    fixture.componentRef.setInput('isNew', true);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector(
        'button[aria-label="Reset registry lock password"]'
      )
    ).toBeNull();
    fixture.componentInstance.requestRegistryLockPasswordReset();
    expect(dialogSpy).not.toHaveBeenCalled();

    // Existing user without registryLockEmailAddress also hides the reset button
    fixture.componentRef.setInput('isNew', false);
    fixture.componentRef.setInput('user', otherUser);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector(
        'button[aria-label="Reset registry lock password"]'
      )
    ).toBeNull();
    fixture.componentInstance.requestRegistryLockPasswordReset();
    expect(dialogSpy).not.toHaveBeenCalled();
  });

  it('should validate form and trigger registry lock password reset in UserEditFormComponent', () => {
    const fixture = TestBed.createComponent(UserEditFormComponent);
    const usersService = TestBed.inject(UsersService);
    usersService.openUserDetails(sampleUser.emailAddress, false);
    const snackBarSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    const dialogSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(true) } as any);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('user', sampleUser);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector(
        'button[aria-label="Reset registry lock password"]'
      )
    ).not.toBeNull();

    let emittedUser: User | undefined;
    component.onEditComplete.subscribe((u) => (emittedUser = u));

    vi.spyOn(component.form().nativeElement, 'checkValidity').mockReturnValue(
      true
    );
    vi.spyOn(component.form().nativeElement, 'reportValidity');
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
    expect(usersService.viewState()).toEqual({ kind: 'list' });

    component.requestRegistryLockPasswordReset();
    expect(snackBarSpy).toHaveBeenCalledWith('Reset failed');
  });
});
