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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { Subject } from 'rxjs';
import { NavigationComponent } from './navigation.component';
import { MaterialModule } from '../material.module';
import { routes, PATHS } from '../app.routes';
import { RegistrarComponent } from '../registrar/registrarsTable.component';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { BackendService } from '../shared/services/backend.service';

describe('NavigationComponent', () => {
  let component: NavigationComponent;
  let fixture: ComponentFixture<NavigationComponent>;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        NavigationComponent,
        MaterialModule,
        RouterModule.forRoot(routes),
      ],
      providers: [
        BackendService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(NavigationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should filter routes to only titled menu nodes and attach parentRoute to children', () => {
    const nodes = component.dataSource.data;
    expect(nodes.every((n) => Boolean(n.title))).toBe(true);

    const settingsNode = nodes.find((n) => n.path === 'settings');
    expect(settingsNode).toBeDefined();
    expect(component.hasChild(0, settingsNode!)).toBe(true);
    expect(settingsNode!.children).toHaveLength(3);
    expect((settingsNode!.children![0] as any).parentRoute.path).toBe(
      'settings'
    );

    const homeNode = nodes.find((n) => n.path === 'home');
    expect(component.hasChild(0, homeNode!)).toBe(false);
  });

  it('should return restricted element IDs for registrars and users routes', () => {
    expect(component.getElementId({ path: RegistrarComponent.PATH })).toBe(
      RESTRICTED_ELEMENTS.REGISTRAR_ELEMENT
    );
    expect(component.getElementId({ path: PATHS.UsersComponent })).toBe(
      RESTRICTED_ELEMENTS.USERS
    );
    expect(component.getElementId({ path: 'home' })).toBeNull();
  });

  it('should navigate to top-level and child routes with merged queryParams', () => {
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    component.onClick({ path: 'domain-list' });
    expect(navigateSpy).toHaveBeenCalledWith(['domain-list'], {
      queryParamsHandling: 'merge',
    });

    const settingsNode = component.dataSource.data.find(
      (n) => n.path === 'settings'
    )!;
    const contactNode = settingsNode.children![0];
    component.onClick(contactNode);
    expect(navigateSpy).toHaveBeenCalledWith(['settings/contact'], {
      queryParamsHandling: 'merge',
    });
  });

  it('should expand parent navigation node when NavigationEnd matches child route', () => {
    const expandSpy = vi.spyOn(component.treeControl, 'expand');
    const eventsSubject = router.events as unknown as Subject<any>;

    eventsSubject.next(
      new NavigationEnd(1, '/settings/security', '/settings/security')
    );

    const settingsNode = component.dataSource.data.find(
      (n) => n.path === 'settings'
    );
    expect(expandSpy).toHaveBeenCalledWith(settingsNode);
  });
});
