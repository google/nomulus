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
import { MaterialModule } from 'src/app/material.module';
import { BackendService } from 'src/app/shared/services/backend.service';
import { ContactComponent } from './contact.component';
import {
  Contact,
  ContactService,
  contactTypeToViewReadyContact,
} from './contact.service';
import { ContactDetailsComponent } from './contactDetails.component';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { GlobalLoaderService } from 'src/app/shared/services/globalLoader.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatCheckboxChange } from '@angular/material/checkbox';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

describe('ContactComponent & ContactService & ContactDetailsComponent', () => {
  let component: ContactComponent;
  let fixture: ComponentFixture<ContactComponent>;
  let contactService: ContactService;
  let backendService: BackendService;
  let registrarIdSignal: ReturnType<typeof signal<string>>;
  let mockSnackBar: { open: ReturnType<typeof vi.fn> };

  const sampleContact: Contact = {
    name: 'Jane Doe',
    emailAddress: 'jane@example.com',
    phoneNumber: '+1.2125550100',
    faxNumber: '+1.2125550101',
    types: ['ADMIN', 'TECH'],
    visibleInRdapAsAdmin: true,
  };

  beforeEach(async () => {
    registrarIdSignal = signal('reg-1');

    await TestBed.configureTestingModule({
      imports: [
        ContactComponent,
        ContactDetailsComponent,
        MaterialModule,
        NoopAnimationsModule,
      ],
      providers: [
        BackendService,
        ContactService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: registrarIdSignal,
            registrars: signal([]),
            registrar: signal(undefined),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    backendService = TestBed.inject(BackendService);
    contactService = TestBed.inject(ContactService);
    vi.spyOn(backendService, 'getContacts').mockReturnValue(
      of([sampleContact])
    );

    fixture = TestBed.createComponent(ContactComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should convert raw Contact to ViewReadyContact with user-friendly types', () => {
    const viewReady = contactTypeToViewReadyContact(sampleContact);
    expect(viewReady.userFriendlyTypes).toEqual([
      'Primary contact',
      'Technical contact',
    ]);
  });

  it('should fetch contacts when registrarId is set and populate dataSource', () => {
    expect(backendService.getContacts).toHaveBeenCalledWith('reg-1');
    expect(component.dataSource.data).toHaveLength(1);
    expect(component.dataSource.data[0].name).toBe('Jane Doe');
  });

  it('should format contact columns cleanly', () => {
    const viewReady = contactTypeToViewReadyContact(sampleContact);
    const byDef = Object.fromEntries(
      component.columns.map((c) => [c.columnDef, c])
    );
    expect(byDef['name'].cell(viewReady)).toBe('Jane Doe');
    expect(byDef['emailAddress'].cell(viewReady)).toBe('jane@example.com');
    expect(byDef['phoneNumber'].cell(viewReady)).toBe('+1.2125550100');
    expect(byDef['faxNumber'].cell(viewReady)).toBe('+1.2125550101');
  });

  it('should switch to contact details view and new contact view', () => {
    const viewReady = contactTypeToViewReadyContact(sampleContact);
    component.openDetails(viewReady);
    expect(contactService.isContactDetailsView()).toBe(true);
    expect(contactService.contactInEdit()).toEqual(viewReady);

    component.openNewContact();
    expect(contactService.isContactNewView()).toBe(true);
    expect(contactService.contactInEdit()?.name).toBe('');
    expect(contactService.contactInEdit()?.types).toEqual(['TECH']);
  });

  it('should perform CRUD operations in ContactService and refresh contacts', () => {
    const viewReady = contactTypeToViewReadyContact(sampleContact);
    const createSpy = vi
      .spyOn(backendService, 'createContact')
      .mockReturnValue(of(sampleContact));
    const updateSpy = vi
      .spyOn(backendService, 'updateContact')
      .mockReturnValue(of(sampleContact));
    const deleteSpy = vi
      .spyOn(backendService, 'deleteContact')
      .mockReturnValue(of(sampleContact));

    contactService.addContact(viewReady).subscribe();
    expect(createSpy).toHaveBeenCalledWith('reg-1', viewReady);

    contactService.updateContact(viewReady).subscribe();
    expect(updateSpy).toHaveBeenCalledWith('reg-1', viewReady);

    contactService.deleteContact(viewReady).subscribe();
    expect(deleteSpy).toHaveBeenCalledWith('reg-1', viewReady);
  });

  describe('ContactDetailsComponent', () => {
    let detailsFixture: ComponentFixture<ContactDetailsComponent>;
    let detailsComponent: ContactDetailsComponent;

    beforeEach(() => {
      contactService.setEditableContact(
        contactTypeToViewReadyContact(structuredClone(sampleContact))
      );
      contactService.isContactDetailsView.set(true);
      detailsFixture = TestBed.createComponent(ContactDetailsComponent);
      mockSnackBar = {
        open: vi
          .spyOn(detailsFixture.debugElement.injector.get(MatSnackBar), 'open')
          .mockReturnValue({} as any),
      };
      detailsComponent = detailsFixture.componentInstance;
      detailsFixture.detectChanges();
    });

    it('should disable ADMIN checkbox and email address when contact has ADMIN type', () => {
      expect(detailsComponent.checkboxIsDisabled('ADMIN')).toBe(true);
      expect(detailsComponent.checkboxIsDisabled('TECH')).toBe(false);
      expect(detailsComponent.emailAddressIsDisabled()).toBe(true);
      expect(detailsComponent.shouldDisplayCheckbox('ADMIN')).toBe(true);
    });

    it('should disable the last remaining contact type checkbox', () => {
      contactService.setEditableContact(
        contactTypeToViewReadyContact({
          ...sampleContact,
          types: ['TECH'],
        })
      );
      expect(detailsComponent.checkboxIsDisabled('TECH')).toBe(true);
      expect(detailsComponent.shouldDisplayCheckbox('ADMIN')).toBe(false);
      expect(detailsComponent.emailAddressIsDisabled()).toBe(false);
    });

    it('should add and remove contact types on checkbox change', () => {
      detailsComponent.checkboxOnChange(
        { checked: true } as MatCheckboxChange,
        'BILLING'
      );
      expect(detailsComponent.contactInEdit()?.types).toContain('BILLING');

      detailsComponent.checkboxOnChange(
        { checked: false } as MatCheckboxChange,
        'BILLING'
      );
      expect(detailsComponent.contactInEdit()?.types).not.toContain('BILLING');
    });

    it('should require at least one contact type when saving', () => {
      detailsComponent.contactInEdit()!.types = [];
      const event = { preventDefault: vi.fn() } as unknown as SubmitEvent;
      detailsComponent.save(event);

      expect(event.preventDefault).toHaveBeenCalled();
      expect(mockSnackBar.open).toHaveBeenCalledWith(
        'Required to select contact type'
      );
    });

    it('should save new or updated contact, track isUpdating and globalLoader while pending, and navigate back', () => {
      const globalLoader = TestBed.inject(GlobalLoaderService);
      const pending$ = new Subject<Contact[]>();
      const updateSpy = vi
        .spyOn(contactService, 'updateContact')
        .mockReturnValue(pending$.asObservable());
      const event = { preventDefault: vi.fn() } as unknown as SubmitEvent;

      detailsComponent.isEditing.set(true);
      detailsComponent.save(event);

      expect(updateSpy).toHaveBeenCalled();
      expect(detailsComponent.isUpdating()).toBe(true);
      expect(globalLoader.isLoading()).toBe(true);

      pending$.next([sampleContact]);
      pending$.complete();

      expect(detailsComponent.isUpdating()).toBe(false);
      expect(globalLoader.isLoading()).toBe(false);
      expect(detailsComponent.isEditing()).toBe(false);
    });

    it('should confirm and delete contact or show error on failure', () => {
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
      const deleteSpy = vi
        .spyOn(contactService, 'deleteContact')
        .mockReturnValueOnce(of([]))
        .mockReturnValueOnce(
          throwError(
            () =>
              new HttpErrorResponse({ error: 'Cannot delete primary contact' })
          )
        );

      detailsComponent.deleteContact();
      expect(deleteSpy).toHaveBeenCalled();
      expect(contactService.isContactDetailsView()).toBe(false);

      detailsComponent.deleteContact();
      expect(mockSnackBar.open).toHaveBeenCalledWith(
        'Cannot delete primary contact'
      );
      confirmSpy.mockRestore();
    });
  });
});
