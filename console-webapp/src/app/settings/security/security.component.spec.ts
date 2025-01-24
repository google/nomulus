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

import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { FormsModule } from '@angular/forms';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { MaterialModule } from 'src/app/material.module';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import SecurityComponent from './security.component';
import { SecurityService } from './security.service';
import SecurityEditComponent from './securityEdit.component';
import { MOCK_REGISTRAR_SERVICE } from 'src/testdata/registrar/registrar.service.mock';

describe('SecurityComponent', () => {
  let component: SecurityComponent;
  let fixture: ComponentFixture<SecurityComponent>;
  let fetchSecurityDetailsSpy: Function;
  let saveSpy: Function;

  beforeEach(async () => {
    const securityServiceSpy = jasmine.createSpyObj(SecurityService, [
      'fetchSecurityDetails',
      'saveChanges',
    ]);

    fetchSecurityDetailsSpy =
      securityServiceSpy.fetchSecurityDetails.and.returnValue(of());

    saveSpy = securityServiceSpy.saveChanges.and.returnValue(of());

    await TestBed.configureTestingModule({
      declarations: [SecurityEditComponent, SecurityComponent],
      imports: [MaterialModule, BrowserAnimationsModule, FormsModule],
      providers: [
        BackendService,
        SecurityService,
        { provide: RegistrarService, useValue: MOCK_REGISTRAR_SERVICE },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    })
      .overrideComponent(SecurityComponent, {
        set: {
          providers: [
            { provide: SecurityService, useValue: securityServiceSpy },
          ],
        },
      })
      .compileComponents();

    fixture = TestBed.createComponent(SecurityComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render security elements', waitForAsync(() => {
    fixture.detectChanges();
    fixture.whenStable().then(() => {
      let listElems: Array<HTMLElement> = Array.from(
        fixture.nativeElement.querySelectorAll('span.console-app__list-value')
      );
      expect(listElems).toHaveSize(8);
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
  }));

  it('should remove ip', waitForAsync(() => {
    component.dataSource.ipAddressAllowList =
      component.dataSource.ipAddressAllowList?.splice(1);
    fixture.whenStable().then(() => {
      fixture.detectChanges();
      let listElems: Array<HTMLElement> = Array.from(
        fixture.nativeElement.querySelectorAll('span.console-app__list-value')
      );
      expect(listElems.map((e) => e.textContent)).toContain(
        'No IP addresses on file.'
      );
    });
  }));

  it('should toggle isEditingSecurity', () => {
    expect(component.securityService.isEditingSecurity).toBeFalse();
    component.editSecurity();
    expect(component.securityService.isEditingSecurity).toBeTrue();
  });

  it('should toggle isEditingPassword', () => {
    expect(component.securityService.isEditingPassword).toBeFalse();
    component.editEppPassword();
    expect(component.securityService.isEditingPassword).toBeTrue();
  });

  it('should call save', waitForAsync(async () => {
    component.editSecurity();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector(
      '.console-app__clientCertificateValue'
    );
    el.value = 'test';
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.nativeElement
      .querySelector('.settings-security__edit-save')
      .click();
    expect(saveSpy).toHaveBeenCalledOnceWith({
      ipAddressAllowList: [{ value: '123.123.123.123' }],
      clientCertificate: 'test',
    });
  }));
});
