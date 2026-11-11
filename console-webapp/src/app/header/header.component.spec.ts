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

import { HeaderComponent } from './header.component';
import { MaterialModule } from '../material.module';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { routes } from '../app.routes';
import { BackendService } from '../shared/services/backend.service';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

describe('HeaderComponent', () => {
  let component: HeaderComponent;
  let fixture: ComponentFixture<HeaderComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent, MaterialModule, RouterModule.forRoot(routes)],
      providers: [
        BackendService,
        { provide: ActivatedRoute, useValue: {} as ActivatedRoute },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should toggle nav pane state and emit toggleNavOpen event', () => {
    const emittedValues: boolean[] = [];
    component.toggleNavOpen.subscribe((val) => emittedValues.push(val));

    component.toggleNavPane();
    component.toggleNavPane();

    expect(emittedValues).toEqual([true, false]);
  });

  it('should redirect to IAP logout URL on logOut()', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    component.logOut();
    expect(openSpy).toHaveBeenCalledWith(
      '/console?gcp-iap-mode=CLEAR_LOGIN_COOKIE',
      '_self'
    );
    openSpy.mockRestore();
  });
});
