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

import SecurityComponent from './security.component';
import { SecurityService, apiToUiConverter } from './security.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MaterialModule } from 'src/app/material.module';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { FormsModule } from '@angular/forms';
import {
  Registrar,
  RegistrarService,
} from 'src/app/registrar/registrar.service';

describe('SecurityComponent', () => {
  let component: SecurityComponent;
  let fixture: ComponentFixture<SecurityComponent>;
  let fetchSecurityDetailsSpy: Function;
  let saveSpy: Function;
  let dummyRegistrarService: RegistrarService;

  beforeEach(async () => {
    const securityServiceSpy = jasmine.createSpyObj(SecurityService, [
      'fetchSecurityDetails',
      'saveChanges',
    ]);

    fetchSecurityDetailsSpy =
      securityServiceSpy.fetchSecurityDetails.and.returnValue(of());

    saveSpy = securityServiceSpy.saveChanges;

    dummyRegistrarService = {
      registrar: { ipAddressAllowList: ['123.123.123.123'] },
    } as RegistrarService;

    await TestBed.configureTestingModule({
      imports: [
        HttpClientTestingModule,
        MaterialModule,
        BrowserAnimationsModule,
        FormsModule,
      ],
      declarations: [SecurityComponent],
      providers: [
        BackendService,
        { provide: RegistrarService, useValue: dummyRegistrarService },
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

  it('should render ip allow list', waitForAsync(() => {
    component.enableEdit();
    fixture.whenStable().then(() => {
      expect(
        Array.from(
          fixture.nativeElement.querySelectorAll(
            '.settings-security__ip-allowlist'
          )
        )
      ).toHaveSize(1);
      expect(
        fixture.nativeElement.querySelector('.settings-security__ip-allowlist')
          .value
      ).toBe('123.123.123.123');
    });
  }));

  it('should remove ip', waitForAsync(() => {
    expect(
      Array.from(
        fixture.nativeElement.querySelectorAll(
          '.settings-security__ip-allowlist'
        )
      )
    ).toHaveSize(1);
    component.removeIpEntry(0);
    fixture.whenStable().then(() => {
      fixture.detectChanges();
      expect(
        Array.from(
          fixture.nativeElement.querySelectorAll(
            '.settings-security__ip-allowlist'
          )
        )
      ).toHaveSize(0);
    });
  }));

  it('should toggle inEdit', () => {
    expect(component.inEdit).toBeFalse();
    component.enableEdit();
    expect(component.inEdit).toBeTrue();
  });

  it('should create temporary data structure', () => {
    expect(component.dataSource).toEqual(
      apiToUiConverter(dummyRegistrarService.registrar)
    );
    component.removeIpEntry(0);
    expect(component.dataSource).toEqual({ ipAddressAllowList: [] });
    expect(dummyRegistrarService.registrar).toEqual({
      ipAddressAllowList: ['123.123.123.123'],
    } as Registrar);
    component.cancel();
    expect(component.dataSource).toEqual(
      apiToUiConverter(dummyRegistrarService.registrar)
    );
  });

  it('should call save', waitForAsync(async () => {
    component.enableEdit();
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement.querySelector(
      '.settings-security__clientCertificate'
    );
    el.value = 'test';
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.nativeElement
      .querySelector('.settings-security__actions-save')
      .click();
    expect(saveSpy).toHaveBeenCalledOnceWith({
      ipAddressAllowList: [{ value: '123.123.123.123' }],
      clientCertificate: 'test',
    });
  }));
});
