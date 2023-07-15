// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import SecurityComponent from './security.component';
import { SecurityService } from './security.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MaterialModule } from 'src/app/material.module';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { FormsModule } from '@angular/forms';

describe('SecurityComponent', () => {
  let component: SecurityComponent;
  let fixture: ComponentFixture<SecurityComponent>;
  let fetchSecurityDetailsSpy: Function;

  beforeEach(async () => {
    const securityServiceSpy = jasmine.createSpyObj(SecurityService, [
      'fetchSecurityDetails',
    ]);
    fetchSecurityDetailsSpy =
      securityServiceSpy.fetchSecurityDetails.and.returnValue(
        of({ ipAddressAllowList: [{ value: '123.123.123.123' }] })
      );
    securityServiceSpy.securitySettings = {
      ipAddressAllowList: [{ value: '123.123.123.123' }],
    };

    await TestBed.configureTestingModule({
      imports: [
        HttpClientTestingModule,
        MaterialModule,
        BrowserAnimationsModule,
        FormsModule,
      ],
      declarations: [SecurityComponent],
      providers: [BackendService],
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

  it('should call fetch spy', () => {
    expect(fetchSecurityDetailsSpy).toHaveBeenCalledTimes(1);
  });

  it('should render ip allow list', () => {
    component.enableEdit();
    fixture.whenStable().then(() => {
      fixture.detectChanges();
      console.log(
        fixture.nativeElement.querySelector('.settings-security__ip-allowlist')
      );
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
  });
});
